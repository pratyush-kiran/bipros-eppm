package com.bipros.ai.tool;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Returns activity rows for the project. Default is unfiltered (every
 * activity) so unqualified questions like "how many activities are there"
 * and "list the activities" return the truthful total. The agent narrows
 * with the {@code status} filter when the user explicitly asks about
 * progress ("what's in flight", "what's almost done", "what hasn't started").
 *
 * Status is derived from {@code percentComplete} + {@code actualStartDate} so
 * the result is consistent even if the persisted {@code status} column is
 * stale (mirrors {@code ActivityService.applyStatusFromProgress}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListActivitiesTool extends ProjectScopedTool {

    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "list_activities";
    }

    @Override
    public String description() {
        return "List activities. By default returns EVERY activity for the project (status=ANY) "
                + "so unqualified questions like 'how many activities are there' or 'list the "
                + "activities' get the full count. Pass an explicit `status` filter "
                + "(IN_PROGRESS, NOT_STARTED, COMPLETED, ANY) and percent-complete window "
                + "ONLY when the user asks about a specific progress slice. "
                + "Optional `edit_status` filter (DRAFT, LOCKED, ANY — default ANY) selects on "
                + "the planning lifecycle: DRAFT = plan still being edited (DPR submission "
                + "rejected); LOCKED = plan frozen, DPRs flow. "
                + "Returns code, name, status, edit_status, percent_complete, planned and "
                + "actual dates, total_float, is_critical. Use this for questions like "
                + "'what's in progress', 'what's almost done', 'what hasn't started', "
                + "'list all draft activities'. "
                + "DO NOT use this tool to answer 'which activities have negative float' / "
                + "'what's behind on float' — the total_float field returned here is the live "
                + "OLTP column on activity.activities and may lag the latest scheduler run by "
                + "design (the scheduler stores its computed float in "
                + "scheduling.schedule_activity_results and only reconciles back to OLTP on a "
                + "later job). For negative-float questions, ALWAYS call "
                + "schedule_advanced(op='negative_float') instead — that reads the "
                + "scheduler-authoritative source and is the only correct answer for those "
                + "questions. Works for the current project or across the user's accessible "
                + "portfolio when no project is selected.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        ArrayNode statusEnum = objectMapper.createArrayNode();
        statusEnum.add("IN_PROGRESS");
        statusEnum.add("NOT_STARTED");
        statusEnum.add("COMPLETED");
        statusEnum.add("ANY");
        ObjectNode statusNode = objectMapper.createObjectNode();
        statusNode.put("type", "string");
        statusNode.set("enum", statusEnum);
        statusNode.put("default", "ANY");
        statusNode.put("description",
                "Progress filter. Default ANY returns every activity — use this for "
                        + "unqualified count / list questions. Narrow to IN_PROGRESS, "
                        + "NOT_STARTED, or COMPLETED only when the user explicitly asks "
                        + "about that slice.");
        props.set("status", statusNode);

        ArrayNode editStatusEnum = objectMapper.createArrayNode();
        editStatusEnum.add("DRAFT");
        editStatusEnum.add("LOCKED");
        editStatusEnum.add("ANY");
        ObjectNode editStatusNode = objectMapper.createObjectNode();
        editStatusNode.put("type", "string");
        editStatusNode.set("enum", editStatusEnum);
        editStatusNode.put("default", "ANY");
        editStatusNode.put("description",
                "Planning lifecycle filter. DRAFT = plan still being edited (DPR submission "
                        + "rejected); LOCKED = plan frozen, DPRs flow. ANY (default) returns both.");
        props.set("edit_status", editStatusNode);

        props.set("limit", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 500).put("default", 100));
        props.set("min_percent_complete", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 100));
        props.set("max_percent_complete", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 100));

        schema.set("properties", props);
        return schema;
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        String statusFilter = input.path("status").asText("ANY").toUpperCase();
        String editStatusFilter = input.path("edit_status").asText("ANY").toUpperCase();
        int limit = Math.max(1, Math.min(500, input.path("limit").asInt(100)));
        Double minPct = input.has("min_percent_complete") ? input.path("min_percent_complete").asDouble() : null;
        Double maxPct = input.has("max_percent_complete") ? input.path("max_percent_complete").asDouble() : null;

        List<Activity> activities;
        if (ctx.projectId() != null) {
            activities = activityRepository.findByProjectId(ctx.projectId());
        } else if ("ADMIN".equals(ctx.role())) {
            // AiContextResolver collapses admin's null sentinel ("no row-level
            // filter, all projects") into an empty scope list — so we must
            // recover the unrestricted intent here, mirroring ListProjectsTool.
            activities = activityRepository.findAll();
        } else {
            List<UUID> scope = ctx.scopedProjectIds();
            if (scope == null || scope.isEmpty()) {
                return ToolResult.error("No accessible projects in scope. Discover projects first, "
                        + "then re-invoke for a specific project or portfolio.");
            }
            activities = activityRepository.findByProjectIdIn(scope);
        }

        List<Activity> filtered = activities.stream()
                .filter(a -> statusMatches(a, statusFilter))
                .filter(a -> editStatusMatches(a, editStatusFilter))
                .filter(a -> minPct == null || (a.getPercentComplete() != null && a.getPercentComplete() >= minPct))
                .filter(a -> maxPct == null || (a.getPercentComplete() != null && a.getPercentComplete() <= maxPct))
                .sorted(Comparator
                        .comparing((Activity a) -> a.getPercentComplete() == null ? 0.0 : a.getPercentComplete(),
                                Comparator.reverseOrder())
                        .thenComparing(a -> a.getCode() == null ? "" : a.getCode()))
                .limit(limit)
                .toList();

        ArrayNode arr = objectMapper.createArrayNode();
        for (Activity a : filtered) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("code", a.getCode());
            o.put("name", a.getName());
            o.put("status", deriveStatus(a).name());
            o.put("edit_status", a.getEditStatus() != null ? a.getEditStatus().name() : null);
            o.put("percent_complete", a.getPercentComplete());
            o.put("planned_start", a.getPlannedStartDate() != null ? a.getPlannedStartDate().toString() : null);
            o.put("planned_finish", a.getPlannedFinishDate() != null ? a.getPlannedFinishDate().toString() : null);
            o.put("actual_start", a.getActualStartDate() != null ? a.getActualStartDate().toString() : null);
            o.put("total_float", a.getTotalFloat());
            o.put("is_critical", Boolean.TRUE.equals(a.getIsCritical()));
            o.put("project_id", a.getProjectId() != null ? a.getProjectId().toString() : null);
            arr.add(o);
        }

        String label = "ANY".equals(statusFilter) ? "activities" : statusFilter.toLowerCase().replace('_', ' ') + " activities";
        return ToolResult.table(
                "Found " + filtered.size() + " " + label
                        + (filtered.size() < activities.size() ? " (filtered from " + activities.size() + ")" : ""),
                arr,
                new String[]{"code", "name", "status", "edit_status", "percent_complete",
                        "planned_start", "planned_finish", "actual_start",
                        "total_float", "is_critical", "project_id"}
        );
    }

    private boolean editStatusMatches(Activity a, String filter) {
        if (filter == null || "ANY".equals(filter)) return true;
        ActivityEditStatus current = a.getEditStatus();
        if (current == null) return false;
        try {
            return current == ActivityEditStatus.valueOf(filter);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean statusMatches(Activity a, String filter) {
        if ("ANY".equals(filter)) return true;
        ActivityStatus derived = deriveStatus(a);
        try {
            return derived == ActivityStatus.valueOf(filter);
        } catch (IllegalArgumentException e) {
            return derived == ActivityStatus.IN_PROGRESS;
        }
    }

    /**
     * Mirrors the canonical mapping in ActivityService.applyStatusFromProgress
     * so we don't trust a possibly-stale `status` column.
     */
    private ActivityStatus deriveStatus(Activity a) {
        Double pct = a.getPercentComplete();
        if (pct != null && pct >= 100.0) {
            return ActivityStatus.COMPLETED;
        }
        if ((pct != null && pct > 0.0) || a.getActualStartDate() != null) {
            return ActivityStatus.IN_PROGRESS;
        }
        return ActivityStatus.NOT_STARTED;
    }
}
