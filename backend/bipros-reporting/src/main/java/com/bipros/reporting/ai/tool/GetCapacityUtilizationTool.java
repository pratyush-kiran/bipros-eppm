package com.bipros.reporting.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.reporting.application.dto.CapacityUtilizationReport;
import com.bipros.reporting.application.service.CapacityUtilizationReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Wraps {@link CapacityUtilizationReportService} so the AI can answer "is the mason crew
 * under-utilised this month under supervisor X" without re-implementing the 3-tier norm chain
 * in SQL. Lives in bipros-reporting because the report service does — the AI module already
 * imports the {@link Tool} contract.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetCapacityUtilizationTool implements Tool {

    private final CapacityUtilizationReportService service;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_capacity_utilization";
    }

    @Override
    public String description() {
        return "Compute capacity utilization for the current project across roles. Returns "
                + "Manpower + Equipment sections, each with per-role rows for Day / Month / "
                + "Cumulative buckets. Every row's bucket carries: qty (the unit-of-work the role "
                + "actually executed — e.g. 10 Cum, 4 nos), budget_days, planned_nos, actual_days, "
                + "actual_nos, actual_days_untracked (days on activities with no norm), "
                + "utilization_pct, cost_implication, plus a norm_source ∈ "
                + "{VARIANT|ROLE|UNSCOPED|NONE} on the row. Resolves the productivity norm via "
                + "the 3-tier chain (VARIANT → ROLE → UNSCOPED). "
                + "Inputs: from_date (default = year start), to_date (default = today), "
                + "norm_type (MANPOWER | EQUIPMENT | omit for both), supervisor_user_id "
                + "(optional — User UUID matching daily_progress_reports.supervisor_user_id), "
                + "work_days (month-bucket denominator, default 26). "
                + "WHEN THE USER NAMES A SUPERVISOR IN PROSE: do NOT call "
                + "resolve_entity(kind='supervisor') — that returns a legacy Resource UUID that "
                + "will NOT match. Call list_project_supervisors(name_filter=<name>, from_date=..., "
                + "to_date=...) FIRST, take the returned supervisor_user_id, and pass it here. "
                + "RESPONSE FORMAT: every per-role line in your prose MUST lead with the qty "
                + "actually executed (\"Carpenter did 10 nos against a 5-day budget\"), then the "
                + "actual_days vs budget_days, THEN the utilization % and cost_implication. "
                + "Don't quote utilization % without the qty + days context — the % is meaningless "
                + "on its own and the user can see it in the UI already. If actual_days_untracked "
                + "is non-null, mention it (\"of those, N days were on activities with no norm\"). "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        addStringProp(props, "from_date", "ISO date (yyyy-MM-dd). Defaults to year start.");
        addStringProp(props, "to_date", "ISO date (yyyy-MM-dd). Defaults to today.");
        addStringProp(props, "norm_type", "MANPOWER, EQUIPMENT, or omit for both.");
        addStringProp(props, "supervisor_user_id",
                "Optional UUID — limits to DPRs filed by this user.");
        ObjectNode workDays = mapper.createObjectNode();
        workDays.put("type", "integer");
        workDays.put("description", "Working days denominator for the month bucket (default 26).");
        props.set("work_days", workDays);
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("get_capacity_utilization requires a project in scope.");
        }

        LocalDate fromDate = parseDate(input, "from_date");
        LocalDate toDate = parseDate(input, "to_date");
        String normType = text(input, "norm_type");
        UUID supervisorUserId = parseUuid(text(input, "supervisor_user_id"));
        int workDays = input != null && input.path("work_days").canConvertToInt()
                ? input.path("work_days").asInt() : 26;

        try {
            CapacityUtilizationReport report = service.build(
                    projectId, fromDate, toDate, "ROLE", normType, supervisorUserId, workDays);
            JsonNode data = mapper.valueToTree(report);

            int mp = report.manpower() != null && report.manpower().rows() != null
                    ? report.manpower().rows().size() : 0;
            int eq = report.equipment() != null && report.equipment().rows() != null
                    ? report.equipment().rows().size() : 0;
            String summary = "Capacity utilization: " + mp + " manpower role row" + plural(mp)
                    + ", " + eq + " equipment role row" + plural(eq) + ".";
            return ToolResult.ok(summary, data);
        } catch (Exception e) {
            log.warn("get_capacity_utilization failed", e);
            return ToolResult.error("Failed to compute capacity utilization: " + e.getMessage());
        }
    }

    private String plural(int n) {
        return n == 1 ? "" : "s";
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
}
