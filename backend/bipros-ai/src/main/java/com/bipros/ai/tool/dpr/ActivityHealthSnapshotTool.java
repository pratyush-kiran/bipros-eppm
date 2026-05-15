package com.bipros.ai.tool.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-call per-activity rollup of DPRs + issues — the answer to "what's going on with each
 * activity" and "how many issues are logged per activity" without chaining list_activities +
 * list_issues. JPA-first, immediately consistent. Project-scoped.
 *
 * <p>The model deflected on the original DPR-page question because it treated each domain
 * tool as independent and stopped after list_activities. This tool collapses the Activity →
 * DPR → Issue walk into one call so even a low-effort routing decision returns a useful
 * answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityHealthSnapshotTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ActivityRepository activityRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DprIssueRepository issueRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "activity_health_snapshot";
    }

    @Override
    public String description() {
        return "One-call per-activity rollup for the current project: DPR count + latest DPR date "
                + "+ issue counts (total, open, resolved, by severity, by category) for every "
                + "activity. Use for \"how many issues per activity\", \"how many DPRs per "
                + "activity\", \"what's going on with each activity\", \"which activity has "
                + "the most field activity / problems\", and any other question that asks for "
                + "per-activity rollups across DPR + issue data. The single call replaces the "
                + "list_activities → list_issues / query_dpr chain and prevents 'no data' "
                + "deflections. Filter to specific activities by passing activity_codes; "
                + "default returns up to 50 activities ordered by total issues then DPR count "
                + "(busiest first). Project-scoped — needs a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode codes = objectMapper.createObjectNode();
        codes.put("type", "array");
        codes.set("items", objectMapper.createObjectNode().put("type", "string"));
        codes.put("description",
                "Optional list of activity codes (e.g. [\"ACT-001\",\"ACT-002\"]). When omitted, "
                        + "every activity in the project is included up to limit.");
        props.set("activity_codes", codes);

        props.set("date_from", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description",
                        "Restrict DPRs and issues to report_date >= date_from. Default: no lower bound."));
        props.set("date_to", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description",
                        "Restrict DPRs and issues to report_date <= date_to. Default: today."));
        props.set("include_cancelled", objectMapper.createObjectNode()
                .put("type", "boolean")
                .put("default", false)
                .put("description",
                        "When true, CANCELLED issues are included in issue counts. Default false."));
        props.set("limit", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT)
                .put("default", DEFAULT_LIMIT)
                .put("description",
                        "Max activities to return. Default 50, capped at 200. Ordering: issues desc, "
                                + "then DPR count desc."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "activity_health_snapshot needs a project in scope. Pick a specific project, then re-ask.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        LocalDate dateFrom = parseDate(input.path("date_from").asText(null));
        LocalDate dateTo = parseDate(input.path("date_to").asText(null));
        boolean includeCancelled = input.path("include_cancelled").asBoolean(false);
        int limit = Math.max(1, Math.min(MAX_LIMIT, input.path("limit").asInt(DEFAULT_LIMIT)));
        Set<String> codeFilter = parseCodeFilter(input.path("activity_codes"));

        List<Activity> activities = activityRepository.findByProjectId(projectId);
        if (!codeFilter.isEmpty()) {
            activities = activities.stream()
                    .filter(a -> a.getCode() != null
                            && codeFilter.contains(a.getCode().toUpperCase(Locale.ROOT)))
                    .toList();
        }

        Map<UUID, Bucket> buckets = new HashMap<>();
        Map<String, Bucket> nameIndex = new HashMap<>();
        for (Activity a : activities) {
            Bucket b = new Bucket(a);
            buckets.put(a.getId(), b);
            if (a.getName() != null) {
                nameIndex.put(a.getName().toLowerCase(Locale.ROOT), b);
            }
        }

        List<DailyProgressReport> dprs =
                dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);
        for (DailyProgressReport d : dprs) {
            if (!inWindow(d.getReportDate(), dateFrom, dateTo)) continue;
            Bucket b = resolveBucket(d.getActivityId(), d.getActivityName(), buckets, nameIndex);
            if (b == null) continue;
            b.recordDpr(d);
        }

        List<DprIssue> issues = issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId);
        int totalIssues = 0;
        int totalOpen = 0;
        Map<String, Integer> projectByCategory = new HashMap<>();
        for (DprIssue i : issues) {
            if (!includeCancelled && i.getStatus() == IssueStatus.CANCELLED) continue;
            if (!inWindow(i.getReportDate(), dateFrom, dateTo)) continue;
            Bucket b = resolveBucket(i.getActivityId(), i.getActivityName(), buckets, nameIndex);
            if (b == null) continue;
            b.recordIssue(i);
            totalIssues++;
            if (isOpen(i.getStatus())) totalOpen++;
            String cat = i.getCategory() != null ? i.getCategory().name() : "OTHER";
            projectByCategory.merge(cat, 1, Integer::sum);
        }

        List<Bucket> ordered = new ArrayList<>(buckets.values());
        Comparator<Bucket> byIssuesDesc = Comparator.comparingInt((Bucket b) -> b.issueTotal).reversed();
        Comparator<Bucket> byDprsDesc = Comparator.comparingInt((Bucket b) -> b.dprCount).reversed();
        Comparator<Bucket> byCode = Comparator.comparing(
                (Bucket b) -> b.activity.getCode() == null ? "" : b.activity.getCode());
        ordered.sort(byIssuesDesc.thenComparing(byDprsDesc).thenComparing(byCode));
        int totalActivities = ordered.size();
        if (ordered.size() > limit) ordered = ordered.subList(0, limit);

        ArrayNode rows = objectMapper.createArrayNode();
        int activitiesWithIssues = 0;
        for (Bucket b : ordered) {
            if (b.issueTotal > 0) activitiesWithIssues++;
            rows.add(b.toJson(objectMapper));
        }

        ObjectNode rollup = objectMapper.createObjectNode();
        rollup.put("total_activities", totalActivities);
        rollup.put("activities_with_issues", activitiesWithIssues);
        rollup.put("total_issues", totalIssues);
        rollup.put("open_issues", totalOpen);
        rollup.put("total_dprs_in_window", buckets.values().stream().mapToInt(b -> b.dprCount).sum());
        rollup.put("top_category_across_project", topKey(projectByCategory));
        if (dateFrom != null) rollup.put("date_from", dateFrom.toString());
        if (dateTo != null) rollup.put("date_to", dateTo.toString());

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("activities", rows);
        wrapper.set("rollup", rollup);
        wrapper.put("returned", ordered.size());

        Map<String, List<UUID>> links = new HashMap<>();
        List<UUID> activityIds = new ArrayList<>();
        for (Bucket b : ordered) activityIds.add(b.activity.getId());
        if (!activityIds.isEmpty()) links.put("activity", activityIds);
        ToolResult.attachLinks(wrapper, links);

        String summary = String.format(
                "%d activit%s — %d with issues, %d total issue%s (%d open) across %d DPR%s.",
                totalActivities, totalActivities == 1 ? "y" : "ies",
                activitiesWithIssues,
                totalIssues, totalIssues == 1 ? "" : "s",
                totalOpen,
                wrapper.get("activities").size(),
                wrapper.get("activities").size() == 1 ? "" : "s");
        return ToolResult.ok(summary, wrapper);
    }

    private static boolean isOpen(IssueStatus status) {
        return status != null && !status.resolvedAtTerminal() && status != IssueStatus.CANCELLED;
    }

    private static boolean inWindow(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null) return from == null && to == null;
        if (from != null && date.isBefore(from)) return false;
        if (to != null && date.isAfter(to)) return false;
        return true;
    }

    private static Bucket resolveBucket(UUID activityId, String activityName,
                                        Map<UUID, Bucket> byId, Map<String, Bucket> byName) {
        if (activityId != null) {
            Bucket b = byId.get(activityId);
            if (b != null) return b;
        }
        if (activityName != null) {
            return byName.get(activityName.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static String topKey(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static Set<String> parseCodeFilter(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Set.of();
        if (!node.isArray()) return Set.of();
        Set<String> out = new HashSet<>();
        node.forEach(n -> {
            String s = n.asText(null);
            if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase(Locale.ROOT));
        });
        return out;
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (Exception e) { return null; }
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of(
                "PROJECT_MANAGER", "PORTFOLIO_MANAGER",
                "SITE_MANAGER", "PROJECT_ENGINEER", "QC_MANAGER", "QA_QC_ENGINEER",
                "BIM_DATA_COORDINATOR",
                "SITE_ENGINEER", "RESOURCE_MANAGER", "SCHEDULER",
                "EXECUTIVE_VIEWER");
    }

    private static final class Bucket {
        final Activity activity;
        int dprCount = 0;
        LocalDate firstDprDate;
        LocalDate latestDprDate;
        UUID latestSupervisorResourceId;
        String latestSupervisorName;
        int issueTotal = 0;
        int issueOpen = 0;
        int issueResolved = 0;
        final Map<String, Integer> bySeverity = new LinkedHashMap<>();
        final Map<String, Integer> byCategory = new LinkedHashMap<>();

        Bucket(Activity activity) {
            this.activity = activity;
        }

        void recordDpr(DailyProgressReport d) {
            dprCount++;
            LocalDate date = d.getReportDate();
            if (date != null) {
                if (firstDprDate == null || date.isBefore(firstDprDate)) firstDprDate = date;
                if (latestDprDate == null || date.isAfter(latestDprDate)) {
                    latestDprDate = date;
                    latestSupervisorResourceId = d.getSupervisorUserId();
                    latestSupervisorName = d.getSupervisorName();
                }
            }
            if (latestSupervisorName == null && d.getSupervisorName() != null) {
                latestSupervisorName = d.getSupervisorName();
                latestSupervisorResourceId = d.getSupervisorUserId();
            }
        }

        void recordIssue(DprIssue i) {
            issueTotal++;
            IssueStatus status = i.getStatus();
            if (isOpen(status)) issueOpen++;
            else if (status != null && status.resolvedAtTerminal()) issueResolved++;
            String sev = i.getSeverity() != null ? i.getSeverity().name() : "MEDIUM";
            bySeverity.merge(sev, 1, Integer::sum);
            String cat = i.getCategory() != null ? i.getCategory().name() : "OTHER";
            byCategory.merge(cat, 1, Integer::sum);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("activity_id", activity.getId() != null ? activity.getId().toString() : null);
            n.put("code", activity.getCode());
            n.put("name", activity.getName());
            n.put("status", activity.getStatus() != null ? activity.getStatus().name() : null);
            n.put("percent_complete", activity.getPercentComplete());
            n.put("is_critical", Boolean.TRUE.equals(activity.getIsCritical()));
            n.put("supervisor_resource_id",
                    latestSupervisorResourceId != null ? latestSupervisorResourceId.toString()
                            : (activity.getResponsibleResourceId() != null
                                    ? activity.getResponsibleResourceId().toString() : null));
            n.put("supervisor_name",
                    latestSupervisorName != null ? latestSupervisorName
                            : activity.getResponsibleResourceName());
            n.put("dpr_count", dprCount);
            n.put("first_dpr_date", firstDprDate != null ? firstDprDate.toString() : null);
            n.put("latest_dpr_date", latestDprDate != null ? latestDprDate.toString() : null);

            ObjectNode issues = mapper.createObjectNode();
            issues.put("total", issueTotal);
            issues.put("open", issueOpen);
            issues.put("resolved", issueResolved);
            issues.set("by_severity", asObject(mapper, bySeverity));
            issues.set("by_category", asObject(mapper, byCategory));
            issues.put("top_category", topKey(byCategory));
            n.set("issues", issues);
            return n;
        }

        private static ObjectNode asObject(ObjectMapper mapper, Map<String, Integer> counts) {
            ObjectNode n = mapper.createObjectNode();
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> n.put(e.getKey(), e.getValue()));
            return n;
        }
    }
}
