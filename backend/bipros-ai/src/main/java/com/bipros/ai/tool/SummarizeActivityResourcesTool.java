package com.bipros.ai.tool;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.resolver.EffectiveRate;
import com.bipros.ai.resolver.EffectiveRateResolver;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates resource assignments across many activities — answers the
 * "what's the Manpower / Material / Equipment split on completed work?" class
 * of question in a SINGLE tool call. Without this, the agent would have to
 * call {@link ListActivityResourcesTool} once per activity, which blows past
 * the orchestrator's per-conversation round budget on any non-trivial project.
 *
 * Filtering happens at the activity level (status / percent-complete / code
 * prefix). The tool then pulls every assignment on the project (one query),
 * keeps the ones whose {@code activity_id} is in the filtered set, and rolls
 * them up by {@link com.bipros.resource.domain.model.ResourceType#getCode()}.
 *
 * Status is derived from {@code percentComplete} + {@code actualStartDate}
 * the same way {@link ListActivitiesTool} does it, so the filter doesn't
 * disagree with what the user just saw in the activities list.
 *
 * Implements {@link Tool} directly (rather than extending
 * {@link ProjectScopedTool}) so {@code @Transactional(readOnly = true)} on
 * {@code execute} actually triggers — the parent class's template-method
 * call to {@code doExecute} is a self-invocation that bypasses Spring's AOP
 * proxy, and we need the open Hibernate session to dereference the lazy
 * {@code Resource#resourceType} association under the orchestrator's worker
 * thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizeActivityResourcesTool implements Tool {

    private final ActivityRepository activityRepository;
    private final ResourceAssignmentRepository assignmentRepository;
    private final ResourceRepository resourceRepository;
    private final EffectiveRateResolver rateResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "summarize_activity_resources";
    }

    @Override
    public String description() {
        return "Aggregate resource ASSIGNMENTS across many activities and return the cost / "
                + "effort split by resource type (Manpower, Material, Equipment). SCOPE: this "
                + "tool only sees resources that have an active resource_assignments row on "
                + "an activity in the current project. For catalogue / rate questions (size "
                + "of the priced master, daily rate of an excavator, most expensive equipment, "
                + "top labor categories by rate, per-MT material rate) use "
                + "query_resource_catalogue — it reads the project-agnostic /v1/resources "
                + "master, which can hold a fully-priced catalogue even when no assignments "
                + "exist on this project. "
                + "Filters activities on the current project by status (COMPLETED / IN_PROGRESS / "
                + "NOT_STARTED / ANY), percent-complete window, or activity-code prefix. "
                + "Returns headline percentage shares (planned and actual), per-type cost "
                + "and unit totals, the top resources inside each type, and a sample of the "
                + "matched activities. Use this any time the question spans more than one "
                + "activity and asks for a breakdown, percentage, or rollup — e.g. \"from "
                + "completed activities, what's the manpower vs material vs equipment split?\", "
                + "\"labour-cost share on the in-progress scope\", \"resource-type mix for "
                + "activities under ACT-1.3\". One call replaces what would otherwise be a "
                + "long chain of per-activity lookups. Each bucket carries override_row_count, "
                + "override_cost_share_pct, and formula_overrides so the AI can disclose when "
                + "project pool overrides or mixed units distort the headline percentages. "
                + "Requires a current project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        ArrayNode statusEnum = objectMapper.createArrayNode();
        statusEnum.add("COMPLETED");
        statusEnum.add("IN_PROGRESS");
        statusEnum.add("NOT_STARTED");
        statusEnum.add("ANY");
        ObjectNode statusNode = objectMapper.createObjectNode();
        statusNode.put("type", "string");
        statusNode.set("enum", statusEnum);
        statusNode.put("default", "ANY");
        statusNode.put("description", "Activity status filter. Status is derived from "
                + "percent-complete and actual start so it stays consistent with list_activities.");
        props.set("status", statusNode);

        props.set("min_percent_complete", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 100)
                .put("description", "Optional. Lower bound on activity percent_complete (inclusive)."));
        props.set("max_percent_complete", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 100)
                .put("description", "Optional. Upper bound on activity percent_complete (inclusive)."));
        props.set("code_prefix", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Optional. Case-insensitive prefix on Activity.code (e.g. "
                        + "\"ACT-1.3\" picks every activity whose code starts with that)."));
        props.set("top_resources_per_type", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 50).put("default", 5)
                .put("description", "How many top resources (by planned cost) to list inside each "
                        + "resource-type bucket. Bigger = more detail, smaller = leaner payload."));

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "summarize_activity_resources needs a project in scope. Pick a specific project, "
                            + "then re-ask. (Activity-resource rollups are project-scoped.)");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        String statusFilter = input.path("status").asText("ANY").toUpperCase();
        Double minPct = input.has("min_percent_complete") ? input.path("min_percent_complete").asDouble() : null;
        Double maxPct = input.has("max_percent_complete") ? input.path("max_percent_complete").asDouble() : null;
        String codePrefix = input.path("code_prefix").asText("").trim().toLowerCase();
        int topN = Math.max(1, Math.min(50, input.path("top_resources_per_type").asInt(5)));

        // 1) Filter activities on the project. Status is derived (not the persisted
        //    column) so this stays in lockstep with what list_activities reports.
        List<Activity> activities = activityRepository.findByProjectId(projectId);
        List<Activity> filtered = new ArrayList<>();
        for (Activity a : activities) {
            if (!statusMatches(a, statusFilter)) continue;
            Double pct = a.getPercentComplete();
            if (minPct != null && (pct == null || pct < minPct)) continue;
            if (maxPct != null && (pct == null || pct > maxPct)) continue;
            if (!codePrefix.isEmpty()
                    && (a.getCode() == null || !a.getCode().toLowerCase().startsWith(codePrefix))) {
                continue;
            }
            filtered.add(a);
        }

        if (filtered.isEmpty()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("filter", buildFilterEcho(statusFilter, minPct, maxPct, codePrefix, 0, List.of()));
            wrapper.set("by_type", objectMapper.createArrayNode());
            wrapper.put("summary_text", buildEmptyFilterSummary(statusFilter, codePrefix));
            return ToolResult.ok(buildEmptyFilterSummary(statusFilter, codePrefix), wrapper);
        }

        Set<UUID> activityIds = new HashSet<>();
        for (Activity a : filtered) activityIds.add(a.getId());

        // 2) Pull every assignment on the project once, keep those whose activity is in scope.
        List<ResourceAssignment> projectAssignments = assignmentRepository.findByProjectId(projectId);
        List<ResourceAssignment> matched = new ArrayList<>();
        Set<UUID> resourceIds = new HashSet<>();
        for (ResourceAssignment ra : projectAssignments) {
            if (ra.getActivityId() == null || !activityIds.contains(ra.getActivityId())) continue;
            matched.add(ra);
            if (ra.getResourceId() != null) resourceIds.add(ra.getResourceId());
        }

        // 3) Hydrate Resource (and through it ResourceType, dereferenced inside the
        //    @Transactional session) in one batch. Mirrors ResourceAssignmentService.hydrate.
        Map<UUID, Resource> resourceById = new HashMap<>();
        if (!resourceIds.isEmpty()) {
            resourceRepository.findAllById(resourceIds).forEach(r -> resourceById.put(r.getId(), r));
        }

        // 4) Bucket by canonical resource-type code.
        Map<String, TypeBucket> buckets = new LinkedHashMap<>();
        // Seed the three canonical types so a zero-count entry still appears (the
        // model can then say "no equipment was used", instead of silently dropping it).
        buckets.put("LABOR", new TypeBucket("LABOR", "Manpower"));
        buckets.put("MATERIAL", new TypeBucket("MATERIAL", "Material"));
        buckets.put("EQUIPMENT", new TypeBucket("EQUIPMENT", "Equipment"));

        for (ResourceAssignment ra : matched) {
            Resource r = ra.getResourceId() == null ? null : resourceById.get(ra.getResourceId());
            String rawType = (r != null && r.getResourceType() != null) ? r.getResourceType().getCode() : null;
            String canonical = canonicalize(rawType);
            String label = friendlyLabel(canonical);
            TypeBucket b = buckets.computeIfAbsent(canonical, k -> new TypeBucket(canonical, label));
            EffectiveRate er = rateResolver.resolve(projectId, ra.getResourceId());
            b.add(ra, r, er);
        }

        // 5) Compute totals + percentages. Cost is the headline (units across types
        //    aren't comparable — bricks vs cement bags vs labour days).
        double totalPlannedCost = 0.0;
        double totalActualCost = 0.0;
        double totalRemainingCost = 0.0;
        int totalAssignments = 0;
        for (TypeBucket b : buckets.values()) {
            totalPlannedCost += b.plannedCost;
            totalActualCost += b.actualCost;
            totalRemainingCost += b.remainingCost;
            totalAssignments += b.assignmentCount;
        }

        ArrayNode byType = objectMapper.createArrayNode();
        // Order: keep buckets with assignments first (sorted desc by planned cost),
        // then any seeded zero-count buckets so the AI sees the full taxonomy.
        List<TypeBucket> sorted = new ArrayList<>(buckets.values());
        sorted.sort(Comparator
                .comparingInt((TypeBucket b) -> b.assignmentCount > 0 ? 0 : 1)
                .thenComparing((TypeBucket b) -> b.plannedCost, Comparator.reverseOrder()));
        for (TypeBucket b : sorted) {
            byType.add(b.toJson(totalPlannedCost, totalActualCost, topN, objectMapper));
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("filter", buildFilterEcho(statusFilter, minPct, maxPct, codePrefix, filtered.size(),
                buildActivitySample(filtered, 25)));
        ObjectNode totals = objectMapper.createObjectNode();
        totals.put("planned_cost", round(totalPlannedCost));
        totals.put("actual_cost", round(totalActualCost));
        totals.put("remaining_cost", round(totalRemainingCost));
        totals.put("assignment_count", totalAssignments);
        wrapper.set("totals", totals);
        wrapper.set("by_type", byType);
        String summary = buildSummaryText(statusFilter, filtered.size(), buckets.values(), totalActualCost, totalPlannedCost);
        wrapper.put("summary_text", summary);

        return ToolResult.ok(summary, wrapper);
    }

    private boolean statusMatches(Activity a, String filter) {
        if ("ANY".equals(filter)) return true;
        ActivityStatus derived = deriveStatus(a);
        try {
            return derived == ActivityStatus.valueOf(filter);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ActivityStatus deriveStatus(Activity a) {
        Double pct = a.getPercentComplete();
        if (pct != null && pct >= 100.0) return ActivityStatus.COMPLETED;
        if ((pct != null && pct > 0.0) || a.getActualStartDate() != null) return ActivityStatus.IN_PROGRESS;
        return ActivityStatus.NOT_STARTED;
    }

    /**
     * Fold legacy aliases into the canonical type code. The ResourceTypeSeeder
     * uses {@code LABOR / EQUIPMENT / MATERIAL}, but earlier datasets and the
     * frontend's {@code formatResourceType} helper accept {@code MANPOWER} and
     * {@code NONLABOR} too. Unknown codes survive verbatim so the AI can call
     * them out by name.
     */
    private static String canonicalize(String code) {
        if (code == null || code.isBlank()) return "UNKNOWN";
        String c = code.trim().toUpperCase();
        return switch (c) {
            case "MANPOWER", "LABOR", "LABOUR" -> "LABOR";
            case "EQUIPMENT", "NONLABOR", "MACHINE", "MACHINERY" -> "EQUIPMENT";
            case "MATERIAL", "MATERIALS" -> "MATERIAL";
            default -> c;
        };
    }

    private static String friendlyLabel(String canonical) {
        return switch (canonical) {
            case "LABOR" -> "Manpower";
            case "EQUIPMENT" -> "Equipment";
            case "MATERIAL" -> "Material";
            case "UNKNOWN" -> "Unknown";
            default -> canonical;
        };
    }

    private ObjectNode buildFilterEcho(String status, Double minPct, Double maxPct, String codePrefix,
                                       int matched, List<ObjectNode> activitySample) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("status", status);
        if (minPct != null) filter.put("min_percent_complete", minPct);
        if (maxPct != null) filter.put("max_percent_complete", maxPct);
        if (!codePrefix.isEmpty()) filter.put("code_prefix", codePrefix);
        filter.put("n_activities_matched", matched);
        ArrayNode arr = objectMapper.createArrayNode();
        activitySample.forEach(arr::add);
        filter.set("activity_sample", arr);
        return filter;
    }

    private List<ObjectNode> buildActivitySample(List<Activity> activities, int cap) {
        List<Activity> sorted = new ArrayList<>(activities);
        sorted.sort(Comparator.comparing((Activity a) -> a.getCode() == null ? "" : a.getCode()));
        List<ObjectNode> out = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, sorted.size()); i++) {
            Activity a = sorted.get(i);
            ObjectNode o = objectMapper.createObjectNode();
            o.put("code", a.getCode());
            o.put("name", a.getName());
            o.put("percent_complete", a.getPercentComplete());
            o.put("status", deriveStatus(a).name());
            out.add(o);
        }
        return out;
    }

    private String buildEmptyFilterSummary(String statusFilter, String codePrefix) {
        StringBuilder sb = new StringBuilder("No activities match the filter (");
        sb.append("status=").append(statusFilter);
        if (!codePrefix.isEmpty()) sb.append(", code_prefix=").append(codePrefix);
        sb.append(").");
        return sb.toString();
    }

    private String buildSummaryText(String statusFilter, int nActivities, java.util.Collection<TypeBucket> buckets,
                                    double totalActualCost, double totalPlannedCost) {
        String scopeLabel = "ANY".equals(statusFilter)
                ? nActivities + " activit" + (nActivities == 1 ? "y" : "ies")
                : nActivities + " " + statusFilter.toLowerCase().replace('_', ' ') + " activit" + (nActivities == 1 ? "y" : "ies");

        // Headline %: prefer actual cost when there's spend; otherwise fall back to planned.
        double basis = totalActualCost > 0 ? totalActualCost : totalPlannedCost;
        if (basis <= 0) {
            return "Across " + scopeLabel + " — no resource cost is recorded yet, so a "
                    + "Manpower / Material / Equipment percentage isn't meaningful.";
        }
        StringBuilder sb = new StringBuilder("Across ").append(scopeLabel).append(" — ");
        boolean first = true;
        for (TypeBucket b : buckets) {
            if (b.assignmentCount == 0) continue;
            double share = totalActualCost > 0 ? b.actualCost : b.plannedCost;
            double pct = share / basis * 100.0;
            if (!first) sb.append(", ");
            sb.append(b.label).append(" ").append(formatPct(pct)).append("%");
            first = false;
        }
        sb.append(totalActualCost > 0 ? " by actual cost." : " by planned cost (no actuals recorded yet).");
        return sb.toString();
    }

    private static String formatPct(double v) {
        if (v >= 99.95) return "100";
        if (v < 0.05) return "0";
        return String.format("%.1f", v);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Per-resource-type accumulator. Holds running cost / unit totals and the
     * matching assignments so we can rank top resources at the end.
     */
    private static final class TypeBucket {
        final String typeCode;
        final String label;
        int assignmentCount;
        double plannedUnits;
        double actualUnits;
        double remainingUnits;
        double plannedCost;
        double actualCost;
        double remainingCost;
        // unitOfMeasure stays non-null only while every assignment so far shares the same unit;
        // mixed units flip it to null and clear the unit-totals from the headline.
        String unit;
        boolean unitsConsistent = true;
        int overrideRowCount;
        double overridePlannedCost;
        double overrideActualCost;
        final Map<UUID, ResourceRollup> perResource = new HashMap<>();
        final Set<UUID> resourceIds = new HashSet<>();

        TypeBucket(String typeCode, String label) {
            this.typeCode = typeCode;
            this.label = label;
        }

        void add(ResourceAssignment ra, Resource r, EffectiveRate er) {
            assignmentCount++;
            double pu = nz(ra.getPlannedUnits());
            double au = nz(ra.getActualUnits());
            double ru = nz(ra.getRemainingUnits());
            double pc = nzBd(ra.getPlannedCost());
            double ac = nzBd(ra.getActualCost());
            double rc = nzBd(ra.getRemainingCost());
            plannedUnits += pu;
            actualUnits += au;
            remainingUnits += ru;
            plannedCost += pc;
            actualCost += ac;
            remainingCost += rc;

            // Prefer the effective unit (pool override's customUnit, else Resource.unit)
            // so the bucket reflects what the project actually sees.
            String thisUnit = er != null ? er.unit() : (r != null ? r.getUnit() : null);
            if (assignmentCount == 1) {
                unit = thisUnit;
            } else if (unitsConsistent) {
                if (thisUnit == null || !thisUnit.equals(unit)) {
                    unit = null;
                    unitsConsistent = false;
                }
            }

            if (er != null && er.overrideApplied()) {
                overrideRowCount++;
                overridePlannedCost += pc;
                overrideActualCost += ac;
            }

            if (r != null) {
                resourceIds.add(r.getId());
                ResourceRollup rr = perResource.computeIfAbsent(r.getId(), k -> new ResourceRollup(r));
                rr.plannedCost += pc;
                rr.actualCost += ac;
                rr.plannedUnits += pu;
                rr.actualUnits += au;
                rr.assignmentCount++;
            }
        }

        ObjectNode toJson(double totalPlannedCost, double totalActualCost, int topN, ObjectMapper m) {
            ObjectNode o = m.createObjectNode();
            o.put("type_code", typeCode);
            o.put("label", label);
            o.put("assignment_count", assignmentCount);
            o.put("distinct_resource_count", resourceIds.size());
            o.put("planned_cost_total", round(plannedCost));
            o.put("actual_cost_total", round(actualCost));
            o.put("remaining_cost_total", round(remainingCost));
            o.put("cost_pct_planned", totalPlannedCost > 0
                    ? Math.round(plannedCost / totalPlannedCost * 1000.0) / 10.0 : null);
            o.put("cost_pct_actual", totalActualCost > 0
                    ? Math.round(actualCost / totalActualCost * 1000.0) / 10.0 : null);
            o.put("units_consistent", unitsConsistent);
            o.put("unit", unit);
            if (unitsConsistent) {
                o.put("planned_units_total", round(plannedUnits));
                o.put("actual_units_total", round(actualUnits));
                o.put("remaining_units_total", round(remainingUnits));
            }

            // Pool-override disclosure: how many rows in this bucket carried a
            // ProjectResource.rateOverride, and what share of cost that represented.
            o.put("override_row_count", overrideRowCount);
            double overrideBasis = actualCost > 0 ? actualCost : plannedCost;
            double overrideShareNum = actualCost > 0 ? overrideActualCost : overridePlannedCost;
            o.put("override_cost_share_pct", overrideBasis > 0
                    ? Math.round(overrideShareNum / overrideBasis * 1000.0) / 10.0 : null);
            ArrayNode notes = m.createArrayNode();
            if (overrideRowCount > 0) notes.add("totals_include_project_pool_overrides");
            if (!unitsConsistent) notes.add("mixed_units_in_bucket");
            o.set("formula_overrides", notes);

            ArrayNode top = m.createArrayNode();
            List<ResourceRollup> rolledUp = new ArrayList<>(perResource.values());
            rolledUp.sort(Comparator.comparingDouble((ResourceRollup r) -> r.plannedCost).reversed());
            for (int i = 0; i < Math.min(topN, rolledUp.size()); i++) {
                ResourceRollup rr = rolledUp.get(i);
                ObjectNode tr = m.createObjectNode();
                tr.put("code", rr.resource.getCode());
                tr.put("name", rr.resource.getName());
                tr.put("role", rr.resource.getRole() != null ? rr.resource.getRole().getName() : null);
                tr.put("assignment_count", rr.assignmentCount);
                tr.put("planned_cost", round(rr.plannedCost));
                tr.put("actual_cost", round(rr.actualCost));
                tr.put("planned_units", round(rr.plannedUnits));
                tr.put("actual_units", round(rr.actualUnits));
                top.add(tr);
            }
            o.set("top_resources", top);
            return o;
        }

        private static double nz(Double v) { return v == null ? 0.0 : v; }
        private static double nzBd(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
    }

    private static final class ResourceRollup {
        final Resource resource;
        int assignmentCount;
        double plannedUnits;
        double actualUnits;
        double plannedCost;
        double actualCost;
        ResourceRollup(Resource r) { this.resource = r; }
    }
}
