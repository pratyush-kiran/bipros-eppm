package com.bipros.ai.tool.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roll up DPR-issues for a single project with filters and group-by. Authoritative + immediately
 * consistent (JPA over OLTP) so the orchestrator's verification pass will see the same numbers
 * on a re-call. Use this for every issue-counting / ranking question; only fall back to
 * {@code query_clickhouse} against {@code fact_dpr_issues_daily} for cross-project trends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListIssuesTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final DprIssueRepository issueRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "list_issues";
    }

    @Override
    public String description() {
        return "List and roll up DPR-issues (field-issue log entries supervisors filed against "
                + "Daily Progress Reports). Filter by activity (id or code), supervisor, severity, "
                + "status, category, and date range. Returns per-issue rows plus rollups grouped "
                + "by activity / supervisor / category / severity / status — pick group_by to match "
                + "the angle of the question. Use for: \"how many issues on activity X\", \"which "
                + "supervisor has logged the most issues\", \"which activity has the most issues\", "
                + "\"what is the reason for issues on X\" (category rollup). CANCELLED issues are "
                + "excluded by default; pass include_cancelled=true to see them. Project-scoped.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("activity_id", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Activity UUID — restricts to issues stamped with this activity."));
        props.set("activity_code", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Activity short code (e.g. ACT-1.3.5). Resolves to activity_id."));
        props.set("supervisor_resource_id", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Filter by the supervisor who logged the issue."));
        props.set("supervisor_name", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Case-insensitive substring on supervisor_name."));
        props.set("assigned_to_resource_id", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Filter by the current assignee (who is looking into it)."));
        props.set("severity", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "One of: LOW, MEDIUM, HIGH, CRITICAL"));
        props.set("status", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "One of: OPEN, IN_PROGRESS, BLOCKED, RESOLVED, CLOSED, CANCELLED"));
        props.set("category", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Issue category enum, e.g. MATERIAL_SHORTAGE, WEATHER, DESIGN_CHANGE."));
        props.set("date_from", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Filter on report_date (inclusive). Defaults: no lower bound."));
        props.set("date_to", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Filter on report_date (inclusive). Defaults: today."));
        props.set("group_by", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description",
                        "Primary rollup axis. One of: activity, supervisor, category, severity, status. "
                                + "Default activity. All four 'by_*' rollups are always returned, but the "
                                + "chosen axis appears first.")
                .put("default", "activity"));
        props.set("include_cancelled", objectMapper.createObjectNode()
                .put("type", "boolean")
                .put("description", "When true, CANCELLED issues are included. Default false.")
                .put("default", false));
        props.set("limit", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT)
                .put("default", DEFAULT_LIMIT)
                .put("description", "Max rows to return. Default 100, capped at 500."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "list_issues needs a project in scope. Pick a specific project, then re-ask.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        UUID activityIdFilter = resolveActivityId(input, projectId);
        UUID supervisorFilter = parseUuid(input.path("supervisor_resource_id"));
        UUID assigneeFilter = parseUuid(input.path("assigned_to_resource_id"));
        String supervisorNameFilter = orNull(input.path("supervisor_name").asText(null));
        IssueSeverity severityFilter = parseEnum(input.path("severity").asText(null), IssueSeverity.class);
        IssueStatus statusFilter = parseEnum(input.path("status").asText(null), IssueStatus.class);
        IssueCategory categoryFilter = parseEnum(input.path("category").asText(null), IssueCategory.class);
        LocalDate dateFrom = parseDate(input.path("date_from").asText(null), null);
        LocalDate dateTo = parseDate(input.path("date_to").asText(null), null);
        boolean includeCancelled = input.path("include_cancelled").asBoolean(false);
        int limit = Math.max(1, Math.min(MAX_LIMIT, input.path("limit").asInt(DEFAULT_LIMIT)));
        String groupBy = orNull(input.path("group_by").asText(null));
        if (groupBy == null) groupBy = "activity";

        List<DprIssue> base = issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId);
        List<DprIssue> filtered = new ArrayList<>();
        Set<IssueStatus> hidden = includeCancelled ? Set.of() : EnumSet.of(IssueStatus.CANCELLED);
        for (DprIssue i : base) {
            if (!includeCancelled && hidden.contains(i.getStatus()) && statusFilter == null) continue;
            if (activityIdFilter != null && !activityIdFilter.equals(i.getActivityId())) continue;
            if (supervisorFilter != null && !supervisorFilter.equals(i.getSupervisorResourceId())) continue;
            if (assigneeFilter != null && !assigneeFilter.equals(i.getAssignedToResourceId())) continue;
            if (supervisorNameFilter != null
                    && (i.getSupervisorName() == null
                            || !i.getSupervisorName().toLowerCase().contains(supervisorNameFilter.toLowerCase())))
                continue;
            if (severityFilter != null && i.getSeverity() != severityFilter) continue;
            if (statusFilter != null && i.getStatus() != statusFilter) continue;
            if (categoryFilter != null && i.getCategory() != categoryFilter) continue;
            if (dateFrom != null && (i.getReportDate() == null || i.getReportDate().isBefore(dateFrom))) continue;
            if (dateTo != null && (i.getReportDate() == null || i.getReportDate().isAfter(dateTo))) continue;
            filtered.add(i);
        }

        int matched = filtered.size();
        List<DprIssue> capped = filtered.size() > limit ? filtered.subList(0, limit) : filtered;

        Map<String, Rollup> byActivity = new HashMap<>();
        Map<UUID, Rollup> bySupervisor = new HashMap<>();
        Map<String, Rollup> byCategory = new HashMap<>();
        Map<String, Rollup> bySeverity = new HashMap<>();
        Map<String, Rollup> byStatus = new HashMap<>();
        int openCount = 0;
        for (DprIssue i : filtered) {
            boolean open = !i.getStatus().resolvedAtTerminal() && i.getStatus() != IssueStatus.CANCELLED;
            if (open) openCount++;
            String act = i.getActivityName() != null ? i.getActivityName() : "(unassigned)";
            byActivity.computeIfAbsent(act, k -> new Rollup(k, null)).add(open, i);
            UUID supKey = i.getSupervisorResourceId();
            String supName = i.getSupervisorName() != null ? i.getSupervisorName() : "(unassigned)";
            bySupervisor.computeIfAbsent(supKey, k -> new Rollup(supName, supKey)).add(open, i);
            String cat = i.getCategory() != null ? i.getCategory().name() : "OTHER";
            byCategory.computeIfAbsent(cat, k -> new Rollup(k, null)).add(open, i);
            String sev = i.getSeverity() != null ? i.getSeverity().name() : "MEDIUM";
            bySeverity.computeIfAbsent(sev, k -> new Rollup(k, null)).add(open, i);
            String st = i.getStatus() != null ? i.getStatus().name() : "OPEN";
            byStatus.computeIfAbsent(st, k -> new Rollup(st, null)).add(open, i);
        }

        ArrayNode rows = objectMapper.createArrayNode();
        Instant now = Instant.now();
        for (DprIssue i : capped) {
            ObjectNode row = objectMapper.createObjectNode();
            row.put("issue_id", i.getId().toString());
            row.put("dpr_id", i.getDprId() != null ? i.getDprId().toString() : null);
            row.put("activity_id", i.getActivityId() != null ? i.getActivityId().toString() : null);
            row.put("activity_name", i.getActivityName());
            row.put("supervisor_resource_id",
                    i.getSupervisorResourceId() != null ? i.getSupervisorResourceId().toString() : null);
            row.put("supervisor_name", i.getSupervisorName());
            row.put("assigned_to_resource_id",
                    i.getAssignedToResourceId() != null ? i.getAssignedToResourceId().toString() : null);
            row.put("assigned_to_name", i.getAssignedToName());
            row.put("title", i.getTitle());
            row.put("description", i.getDescription());
            row.put("category", i.getCategory() != null ? i.getCategory().name() : null);
            row.put("severity", i.getSeverity() != null ? i.getSeverity().name() : null);
            row.put("status", i.getStatus() != null ? i.getStatus().name() : null);
            row.put("report_date", i.getReportDate() != null ? i.getReportDate().toString() : null);
            row.put("opened_at", i.getOpenedAt() != null ? i.getOpenedAt().toString() : null);
            row.put("resolved_at", i.getResolvedAt() != null ? i.getResolvedAt().toString() : null);
            row.put("age_hours", ageHours(i, now));
            row.put("resolution_notes", i.getResolutionNotes());
            rows.add(row);
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", rows);
        wrapper.set("by_activity", rollupArray(byActivity, "activity_name"));
        wrapper.set("by_supervisor", rollupArray(bySupervisor.values(), "supervisor"));
        wrapper.set("by_category", rollupArray(byCategory, "category"));
        wrapper.set("by_severity", rollupArray(bySeverity, "severity"));
        wrapper.set("by_status", rollupArray(byStatus, "status"));
        wrapper.put("matched", matched);
        wrapper.put("returned", capped.size());
        wrapper.put("open_count", openCount);
        wrapper.put("primary_group_by", groupBy);
        if (dateFrom != null) wrapper.put("date_from", dateFrom.toString());
        if (dateTo != null) wrapper.put("date_to", dateTo.toString());

        Map<String, List<UUID>> links = new HashMap<>();
        if (activityIdFilter != null) links.put("activity", List.of(activityIdFilter));
        if (supervisorFilter != null) links.put("supervisor", List.of(supervisorFilter));
        ToolResult.attachLinks(wrapper, links);

        String summary = String.format(
                "%d DPR issue%s (%d open) — %d activit%s, %d superviso%s. Top category: %s.",
                matched, matched == 1 ? "" : "s", openCount,
                byActivity.size(), byActivity.size() == 1 ? "y" : "ies",
                bySupervisor.size(), bySupervisor.size() == 1 ? "r" : "rs",
                topKey(byCategory));
        return ToolResult.ok(summary, wrapper);
    }

    private ArrayNode rollupArray(Map<String, Rollup> by, String labelKey) {
        return rollupArray(by.values(), labelKey);
    }

    private ArrayNode rollupArray(Iterable<Rollup> rollups, String labelKey) {
        List<Rollup> list = new ArrayList<>();
        rollups.forEach(list::add);
        list.sort(Comparator.comparingInt((Rollup r) -> r.count).reversed());
        ArrayNode out = objectMapper.createArrayNode();
        for (Rollup r : list) {
            ObjectNode n = objectMapper.createObjectNode();
            if ("supervisor".equals(labelKey)) {
                n.put("supervisor_resource_id", r.id != null ? r.id.toString() : null);
                n.put("supervisor_name", r.label);
            } else {
                n.put(labelKey, r.label);
            }
            n.put("count", r.count);
            n.put("open_count", r.openCount);
            n.put("top_category", topKey(r.byCategory));
            out.add(n);
        }
        return out;
    }

    private static String topKey(Map<String, Rollup> by) {
        return by.values().stream()
                .max(Comparator.comparingInt(r -> r.count))
                .map(r -> r.label)
                .orElse(null);
    }

    private static Double ageHours(DprIssue i, Instant now) {
        if (i.getOpenedAt() == null) return null;
        Instant end = i.getResolvedAt() != null ? i.getResolvedAt() : now;
        return Duration.between(i.getOpenedAt(), end).toMillis() / 3_600_000.0;
    }

    private UUID resolveActivityId(JsonNode input, UUID projectId) {
        String idStr = orNull(input.path("activity_id").asText(null));
        if (idStr != null) {
            try {
                UUID id = UUID.fromString(idStr);
                Optional<Activity> a = activityRepository.findById(id);
                if (a.isPresent() && projectId.equals(a.get().getProjectId())) return id;
            } catch (IllegalArgumentException ignored) {
            }
        }
        String code = orNull(input.path("activity_code").asText(null));
        if (code != null) {
            return activityRepository.findByProjectIdAndCode(projectId, code)
                    .map(Activity::getId).orElse(null);
        }
        return null;
    }

    private static UUID parseUuid(JsonNode node) {
        String s = orNull(node == null ? null : node.asText(null));
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static <E extends Enum<E>> E parseEnum(String s, Class<E> type) {
        if (s == null || s.isBlank()) return null;
        try { return Enum.valueOf(type, s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return LocalDate.parse(raw.trim()); } catch (Exception e) { return fallback; }
    }

    private static String orNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static class Rollup {
        final String label;
        final UUID id;
        int count = 0;
        int openCount = 0;
        final Map<String, Rollup> byCategory = new LinkedHashMap<>();

        Rollup(String label, UUID id) {
            this.label = label;
            this.id = id;
        }

        void add(boolean open, DprIssue i) {
            count++;
            if (open) openCount++;
            String cat = i.getCategory() != null ? i.getCategory().name() : "OTHER";
            byCategory.computeIfAbsent(cat, k -> new Rollup(k, null)).countOnly();
        }

        void countOnly() {
            count++;
        }
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
}
