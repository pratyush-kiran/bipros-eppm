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
        return "Capacity EFFICIENCY view (output vs productivity norm per resource-day) — "
                + "answers \"did this role produce the budgeted qty per day\". This is DIFFERENT "
                + "from the DEPLOYMENT view (`deployment_utilization`) which answers \"how much "
                + "of the AVAILABLE capacity was actually used\" (actual/available). Use both "
                + "when the user's question is broad about capacity. Use THIS tool for: "
                + "\"is the mason crew under-utilised vs the norm\", \"efficiency %\", "
                + "\"productivity vs budgeted norm\", \"cost implication of low output\". "
                + "Returns Manpower + Equipment sections, each with per-role rows that EACH "
                + "carry THREE TIME BUCKETS in one response (so one tool call answers all three "
                + "time-period flavors of the question): "
                + "(a) `forTheDay` — single-day snapshot anchored on TODAY when today falls "
                + "inside [from_date, to_date], otherwise anchored on to_date (the last day of "
                + "the window). "
                + "(b) `forTheMonth` — calendar month of the same anchor day (e.g., May 1–31 if "
                + "anchor is May 22). "
                + "(c) `cumulative` — the full [from_date, to_date] window. "
                + "TIME-BUCKET ROUTING (MANDATORY): "
                + "• User says \"today\" / \"for the day\" / \"on date X\" → lead with the "
                + "forTheDay bucket. "
                + "• User says \"this month\" / \"for the month\" / \"in May\" → lead with the "
                + "forTheMonth bucket. "
                + "• User says \"cumulative\" / \"to date\" / \"so far\" / \"across the "
                + "project\" / \"across the window\" → lead with the cumulative bucket. "
                + "• User is vague (\"what's the utilization\") → quote all three explicitly, "
                + "labelled, so the user can compare. NEVER quote one bucket without saying "
                + "which it is. "
                + "Each bucket carries the SAME fields: qty (the unit-of-work the role actually "
                + "executed — ALREADY allocated per the per-DPR allocator AND ALREADY net of "
                + "sub-contractor qty), budgetDays, plannedNos, actualDays, actualNos, "
                + "actualDaysUntracked (days on activities with no norm), actualDaysOnHiddenSides "
                + "(days the role showed up but its side was suppressed by SERIES/SUBSTITUTE), "
                + "utilizationPct, costImplication, plus normResolved (false = no norm for this "
                + "role on the activity — show \"—\" for budget/eff) and a normSource ∈ "
                + "{VARIANT|ROLE|UNSCOPED|NONE} on the row. The 3-tier norm chain "
                + "(VARIANT → ROLE → UNSCOPED) is applied automatically. "
                + "Sections also carry hiddenSideNotes[] (see below). "
                + "PER-DPR ALLOCATION: workdone is split across roles in proportion to "
                + "(resolvedNorm × NOS) — never the full DPR qty to every role. "
                + "SUB-CONTRACTOR NETTING: when a DPR records sub-contractor work, that qty is "
                + "subtracted from workdone BEFORE allocation, so the qty column reflects "
                + "company-resource output only. For SC-specific questions use "
                + "get_subcontractor_kpis. "
                + "HIDDEN-SIDE NOTES: each section's hiddenSideNotes[] lists activities where "
                + "this side was suppressed by SERIES (smaller-expected side wins, losing side "
                + "hidden) or SUBSTITUTE (larger-expected side wins, losing side hidden). When "
                + "an activity appears in hiddenSideNotes, say so verbatim using the "
                + "governingSide + mode the note carries — e.g., \"Equipment utilization not "
                + "applicable for ACT-2-1-5-I on 22 May — Manpower governed the day (SERIES).\" "
                + "Never invent your own explanation. "
                + "HRS IS LOGGING-ONLY: this tool never multiplies HRS into productivity. "
                + "Norms are per-day NOS only. "
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
                + "If hiddenSideNotes is non-empty for the section the user asked about, cite the "
                + "note in your answer. "
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
