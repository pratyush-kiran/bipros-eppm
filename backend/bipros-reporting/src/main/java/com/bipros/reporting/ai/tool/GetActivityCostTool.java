package com.bipros.reporting.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.reporting.application.service.ActivityCostQueryService;
import com.bipros.reporting.application.service.ActivityCostQueryService.ActivityCostReport;
import com.bipros.reporting.application.service.ActivityCostQueryService.ActivityCostRow;
import com.bipros.reporting.application.service.ActivityCostQueryService.Breakdown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Activity-level cost question answerer. Returns planned / actual / remaining for an activity
 * plus an optional breakdown (by ROLE | DAY | SUPERVISOR | RESOURCE_TYPE). Lives in
 * bipros-reporting because {@link ActivityCostQueryService} does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetActivityCostTool implements Tool {

    private final ActivityCostQueryService costService;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_activity_cost";
    }

    @Override
    public String description() {
        return "Get planned / actual / remaining cost for an activity AND the currently-assigned "
                + "supervisor for that activity. Inputs: activity_id (UUID) OR activity_code "
                + "(e.g. ACT-1.3.5(i)a) — at least one is required. Optional from_date / to_date / "
                + "date (single day) narrow actual cost to a date window. Optional "
                + "supervisor_user_id attributes cost to a specific DPR-level supervisor (uses "
                + "dpr.supervisor_user_id, NOT activity.supervisor_user_id). Optional "
                + "breakdown_by ∈ {ROLE, DAY, SUPERVISOR, RESOURCE_TYPE, NONE} controls the rows "
                + "array. SUPERVISORS on the activity (multi-supervisor model): the response "
                + "carries an `assigned_supervisors` ARRAY with every supervisor on the activity "
                + "({user_id, name} per row, no primary marker — all are equal). USE THIS ARRAY for "
                + "'who is the supervisor of activity X' / 'list supervisors of activity X' / 'who "
                + "supervises X' questions and list every name. The singular "
                + "`assigned_supervisor_user_id` + `assigned_supervisor_name` are the legacy "
                + "first-entry cache kept in sync with the array (useful if the user explicitly "
                + "asks for ONE name) but they ARE NOT the full picture and an activity can have "
                + "5+ supervisors. Do NOT call list_supervisors for this — it's keyed on the legacy "
                + "responsible_resource_id which is null in the new role-rate model.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        addStringProp(props, "activity_id", "UUID. Use this if you already have it.");
        addStringProp(props, "activity_code", "Activity code, e.g. ACT-1.3.5(i)a.");
        addStringProp(props, "from_date", "ISO date (yyyy-MM-dd) — lower bound for actual cost.");
        addStringProp(props, "to_date", "ISO date (yyyy-MM-dd) — upper bound for actual cost.");
        addStringProp(props, "date",
                "ISO date — shortcut for a single day (sets from_date = to_date = date).");
        addStringProp(props, "supervisor_user_id",
                "Optional UUID — limit to DPRs where supervisor_user_id matches.");
        addStringProp(props, "breakdown_by",
                "ROLE | DAY | SUPERVISOR | RESOURCE_TYPE | NONE (default ROLE).");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode());
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String activityIdStr = text(input, "activity_id");
        String activityCode = text(input, "activity_code");
        if (activityIdStr == null && activityCode == null) {
            return ToolResult.error("Either activity_id or activity_code is required.");
        }

        LocalDate fromDate = parseDate(input, "from_date");
        LocalDate toDate = parseDate(input, "to_date");
        LocalDate date = parseDate(input, "date");
        if (date != null) {
            fromDate = date;
            toDate = date;
        }

        UUID supervisorUserId = parseUuid(text(input, "supervisor_user_id"));
        Breakdown breakdown;
        try {
            String bd = text(input, "breakdown_by");
            breakdown = bd == null ? Breakdown.ROLE : Breakdown.valueOf(bd.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("breakdown_by must be one of ROLE, DAY, SUPERVISOR, RESOURCE_TYPE, NONE.");
        }

        ActivityCostReport report;
        try {
            if (activityIdStr != null) {
                UUID id = UUID.fromString(activityIdStr);
                report = costService.queryByActivityId(id, fromDate, toDate, supervisorUserId, breakdown);
            } else {
                report = costService.queryByActivityCode(
                        ctx.projectId(), activityCode, fromDate, toDate, supervisorUserId, breakdown);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.warn("get_activity_cost failed", e);
            return ToolResult.error("Failed to compute activity cost: " + e.getMessage());
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("activity_id",
                report.activityId() != null ? report.activityId().toString() : null);
        wrapper.put("activity_code", report.activityCode());
        wrapper.put("activity_name", report.activityName());
        wrapper.put("project_id", report.projectId() != null ? report.projectId().toString() : null);
        // Multi-supervisor model: emit the full set first, then the legacy singular cache for
        // back-compat. The AI's SUPERVISOR LOOKUP prompt block routes "who supervises X" to the
        // array — see assigned_supervisors below.
        ArrayNode supsArr = mapper.createArrayNode();
        if (report.assignedSupervisors() != null) {
            for (var s : report.assignedSupervisors()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("user_id", s.userId() != null ? s.userId().toString() : null);
                row.put("name", s.name());
                supsArr.add(row);
            }
        }
        wrapper.set("assigned_supervisors", supsArr);
        wrapper.put("assigned_supervisors_count", supsArr.size());
        // Legacy first-entry cache — equals the first row of assigned_supervisors when the
        // activity has any. Useful only when the user explicitly asks for "the primary" or
        // "first" supervisor; otherwise lead with the array.
        wrapper.put("assigned_supervisor_user_id",
                report.assignedSupervisorUserId() != null
                        ? report.assignedSupervisorUserId().toString() : null);
        wrapper.put("assigned_supervisor_name",
                report.assignedSupervisorName() != null && !report.assignedSupervisorName().isBlank()
                        ? report.assignedSupervisorName() : null);
        wrapper.put("from_date", report.fromDate() != null ? report.fromDate().toString() : null);
        wrapper.put("to_date", report.toDate() != null ? report.toDate().toString() : null);
        wrapper.put("supervisor_user_id",
                report.supervisorFilter() != null ? report.supervisorFilter().toString() : null);
        wrapper.put("breakdown_by", report.breakdown() != null ? report.breakdown().name() : "NONE");
        wrapper.put("planned_cost", asString(report.plannedCost()));
        wrapper.put("actual_cost", asString(report.actualCost()));
        wrapper.put("remaining_cost", asString(report.remainingCost()));

        ArrayNode rows = mapper.createArrayNode();
        for (ActivityCostRow r : report.rows()) {
            ObjectNode row = mapper.createObjectNode();
            row.put("dimension", r.dimension());
            row.put("label", r.label());
            row.put("planned_cost", asString(r.plannedCost()));
            row.put("actual_cost", asString(r.actualCost()));
            row.put("remaining_cost", asString(r.remainingCost()));
            if (r.warning() != null) row.put("warning", r.warning());
            rows.add(row);
        }
        wrapper.set("rows", rows);
        if (report.warning() != null) wrapper.put("warning", report.warning());

        String summary = "Planned ₹" + asString(report.plannedCost())
                + " · Actual ₹" + asString(report.actualCost())
                + " · Remaining ₹" + asString(report.remainingCost())
                + " · " + rows.size() + " breakdown row" + (rows.size() == 1 ? "" : "s") + ".";
        return ToolResult.ok(summary, wrapper);
    }

    private void addStringProp(ObjectNode props, String name, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        props.set(name, n);
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parseDate(JsonNode in, String field) {
        String s = text(in, field);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String asString(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}
