package com.bipros.reporting.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison.EquipmentDelta;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison.TradeDelta;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ActivityDrillDown;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.EquipmentRollup;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PeriodMetrics;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PeriodMetricsBuckets;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PlannedActuals;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PlannedActualsBuckets;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ProductivityNorms;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ResourceLine;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.Summary;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.TradeRollup;
import com.bipros.reporting.application.service.SupervisorPerformanceReportService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supervisor-scoped capacity performance — answers "compare supervisors", "activity drill-down
 * for supervisor X", "who is the best supervisor for Helpers this month". Wraps
 * {@link SupervisorPerformanceReportService}:
 *
 * <ul>
 *   <li>1 supervisor id → {@code build()} → one report with per-activity drill-down.</li>
 *   <li>2+ supervisor ids → {@code compare()} → N reports + per-trade / per-equipment deltas
 *       with server-computed {@code bestSupervisorId} (no client-side max-of math).</li>
 * </ul>
 *
 * <p>The per-trade / per-equipment rollups already apply the per-DPR allocator and net
 * sub-contractor qty, surfacing {@code actualDaysOnHiddenSides} (suppressed by SERIES /
 * SUBSTITUTE) and {@code actualDaysUntracked} (no norm) so the LLM can phrase
 * "(X tracked · Y suppressed · Z untracked)" like the UI does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSupervisorPerformanceTool implements Tool {

    private final SupervisorPerformanceReportService service;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_supervisor_performance";
    }

    @Override
    public String description() {
        return "Supervisor-scoped capacity / productivity report with activity drill-down. "
                + "Pass 1 supervisor_user_id to get that supervisor's per-trade + per-equipment "
                + "rollup PLUS per-activity breakdown (Foreman/Helper/Supervisor on activity "
                + "ACT-2-7-6-I etc.) for the date window. Pass 2 OR MORE supervisor_user_ids "
                + "to additionally get trade_deltas and equipment_deltas with a "
                + "SERVER-COMPUTED bestSupervisorId per trade/equipment — never compute the "
                + "best-of yourself; quote the tool's bestSupervisorId verbatim. "
                + "Returns: reports[] (one per supervisor), each with summary.manpower[] "
                + "(TradeRollup: tradeKey/label, mmRate, qtyDone [allocated], "
                + "budgetedManDays, actualManDays, actualDaysOnHiddenSides, "
                + "actualDaysUntracked, utilizationPct, costImplication, normSource), "
                + "summary.equipment[] (same shape), summary.manpowerHiddenNotes[] + "
                + "equipmentHiddenNotes[], and activities[] (per-activity drill-down with "
                + "qtyForMonth, subContractorQty, resources[] carrying ProductivityNorms + "
                + "PlannedActuals per role / equipment). When comparing, also returns "
                + "trade_deltas[]/equipment_deltas[] keyed by trade/equipment, each carrying "
                + "bySupervisor map + bestSupervisorId + bestUtilizationPct. "
                + "USE THIS TOOL FOR: 'compare Illayaraja and Md Saiffuddin', 'who is the best "
                + "supervisor for Helpers this month', 'activity-level breakdown for supervisor "
                + "X', 'on which activities was equipment governing under SERIES for supervisor "
                + "Y', 'how many days were suppressed for Carpenter under Illayaraja'. "
                + "USE get_capacity_utilization (NOT this tool) FOR project-wide role-level "
                + "rollup without per-supervisor or per-activity breakdown. "
                + "RESOLVING SUPERVISOR NAMES: call list_project_supervisors(name_filter=…) "
                + "FIRST to convert names to User UUIDs — do NOT use resolve_entity for "
                + "supervisors (that returns legacy Resource UUIDs and will NOT match). "
                + "RESPONSE FORMAT: per-role line MUST lead with allocated qty + budget days + "
                + "actual days, then utilization percent and cost implication. When "
                + "actualDaysOnHiddenSides or actualDaysUntracked is non-zero, render as "
                + "'(X tracked · Y suppressed · Z untracked)'. When subContractorQty > 0 on an "
                + "activity, say 'qty_total = company + sub_contractor' explicitly. When "
                + "manpowerHiddenNotes or equipmentHiddenNotes is non-empty, cite each note "
                + "verbatim (governingSide + mode) — do not invent your own explanation. "
                + "TIME-PERIOD HANDLING (2026-05-25): the response now carries Day / "
                + "CalendarMonth / Cumulative buckets directly — for each trade/equipment "
                + "rollup in summary.manpower[] / summary.equipment[] AND for each ResourceLine "
                + "in activities[].resources[] — so a SINGLE call answers all three time-period "
                + "flavours within the requested [from_date, to_date] window. Anchor rule: "
                + "the 'day' bucket is anchored on TODAY when today is inside the window, else "
                + "on to_date. 'calendarMonth' is the calendar month of that anchor day. "
                + "'cumulative' covers the full window. The same anchor applies at the "
                + "activity-header level (qty_for_day / qty_for_calendar_month / "
                + "qty_cumulative_window). "
                + "ROUTING: "
                + "• user says 'today' / 'for the day' / 'on date X' → lead with the .day "
                + "bucket from buckets (or actual_buckets / plan_buckets on ResourceLine). "
                + "• user says 'this month' / 'for the month' → lead with .calendar_month. "
                + "• user says 'cumulative' / 'so far' / 'across the window' → lead with "
                + ".cumulative (which matches the legacy flat fields). "
                + "• user is vague → quote all three explicitly labelled. "
                + "• 'last 7 days' or other custom ranges → vary from_date / to_date AND quote "
                + ".cumulative — the day/calendarMonth slices are anchored within the window, "
                + "not equivalent to the custom range. "
                + "For multi-period TRENDS (week-by-week, month-by-month time series across a "
                + "long window), use `get_capacity_utilization_trend` instead. "
                + "Inputs: supervisor_user_ids (array of UUIDs, required, 1 or more), "
                + "from_date (default = first day of current month), to_date (default = "
                + "today), work_days (default 26 — month-bucket denominator). "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();

        ObjectNode ids = mapper.createObjectNode();
        ids.put("type", "array");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        items.put("format", "uuid");
        ids.set("items", items);
        ids.put("minItems", 1);
        ids.put("description",
                "Array of User UUIDs (1 or more). 1 → single-supervisor report with activity "
                        + "drill-down. 2+ → comparison with server-computed best-per-trade. "
                        + "Resolve names via list_project_supervisors first.");
        props.set("supervisor_user_ids", ids);

        props.set("from_date", str("ISO date (yyyy-MM-dd). Default: first day of current month."));
        props.set("to_date", str("ISO date (yyyy-MM-dd). Default: today."));

        ObjectNode workDays = mapper.createObjectNode();
        workDays.put("type", "integer");
        workDays.put("default", 26);
        workDays.put("description",
                "Working days in the month; controls budgetedManDays derivation.");
        props.set("work_days", workDays);

        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("supervisor_user_ids");
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "get_supervisor_performance requires a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null
                        || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        List<UUID> supervisorIds = readUuidArray(input, "supervisor_user_ids");
        if (supervisorIds.isEmpty()) {
            return ToolResult.error(
                    "supervisor_user_ids is required (1 or more User UUIDs). "
                            + "Call list_project_supervisors(name_filter=…) to resolve names first.");
        }

        LocalDate to = parseDate(input, "to_date", LocalDate.now());
        LocalDate from = parseDate(input, "from_date", to.withDayOfMonth(1));
        if (from.isAfter(to)) {
            LocalDate t = from; from = to; to = t;
        }
        int workDays = input.path("work_days").isInt() ? input.path("work_days").asInt() : 26;
        if (workDays <= 0) workDays = 26;

        try {
            ObjectNode out = mapper.createObjectNode();
            out.put("project_id", projectId.toString());
            out.put("from_date", from.toString());
            out.put("to_date", to.toString());
            out.put("work_days", workDays);
            out.put("supervisor_count", supervisorIds.size());

            ArrayNode reportsJson = mapper.createArrayNode();
            ArrayNode tradeDeltasJson = mapper.createArrayNode();
            ArrayNode equipmentDeltasJson = mapper.createArrayNode();

            if (supervisorIds.size() == 1) {
                SupervisorPerformanceReport r = service.build(
                        projectId, supervisorIds.get(0), from, to, workDays);
                reportsJson.add(renderReport(r));
            } else {
                SupervisorPerformanceComparison cmp = service.compare(
                        projectId, supervisorIds, from, to, workDays);
                if (cmp.reports() != null) {
                    for (SupervisorPerformanceReport r : cmp.reports()) {
                        reportsJson.add(renderReport(r));
                    }
                }
                if (cmp.tradeDeltas() != null) {
                    for (TradeDelta d : cmp.tradeDeltas()) {
                        tradeDeltasJson.add(renderTradeDelta(d));
                    }
                }
                if (cmp.equipmentDeltas() != null) {
                    for (EquipmentDelta d : cmp.equipmentDeltas()) {
                        equipmentDeltasJson.add(renderEquipmentDelta(d));
                    }
                }
            }
            out.set("reports", reportsJson);
            out.set("trade_deltas", tradeDeltasJson);
            out.set("equipment_deltas", equipmentDeltasJson);

            return ToolResult.ok(buildSummary(supervisorIds.size(), reportsJson.size(),
                    tradeDeltasJson.size(), equipmentDeltasJson.size(), from, to), out);
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("get_supervisor_performance: " + iae.getMessage());
        } catch (Exception e) {
            log.warn("get_supervisor_performance failed", e);
            return ToolResult.error(
                    "Failed to compute supervisor performance: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────── rendering

    private ObjectNode renderReport(SupervisorPerformanceReport r) {
        ObjectNode n = mapper.createObjectNode();
        if (r.projectId() != null) n.put("project_id", r.projectId().toString());
        if (r.supervisorUserId() != null)
            n.put("supervisor_user_id", r.supervisorUserId().toString());
        if (r.supervisorName() != null) n.put("supervisor_name", r.supervisorName());
        if (r.fromDate() != null) n.put("from_date", r.fromDate().toString());
        if (r.toDate() != null) n.put("to_date", r.toDate().toString());
        if (r.referenceDate() != null) n.put("reference_date", r.referenceDate().toString());
        n.put("work_days", r.workDays());
        if (r.summary() != null) n.set("summary", renderSummary(r.summary()));
        ArrayNode activities = mapper.createArrayNode();
        if (r.activities() != null) {
            for (ActivityDrillDown a : r.activities()) activities.add(renderActivity(a));
        }
        n.set("activities", activities);
        return n;
    }

    private ObjectNode renderSummary(Summary s) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode mp = mapper.createArrayNode();
        if (s.manpower() != null) {
            for (TradeRollup t : s.manpower()) mp.add(renderTradeRollup(t));
        }
        n.set("manpower", mp);
        ArrayNode eq = mapper.createArrayNode();
        if (s.equipment() != null) {
            for (EquipmentRollup e : s.equipment()) eq.add(renderEquipmentRollup(e));
        }
        n.set("equipment", eq);
        n.set("manpower_hidden_notes", renderHiddenNotes(s.manpowerHiddenNotes()));
        n.set("equipment_hidden_notes", renderHiddenNotes(s.equipmentHiddenNotes()));
        return n;
    }

    private ObjectNode renderTradeRollup(TradeRollup t) {
        ObjectNode n = mapper.createObjectNode();
        if (t.tradeKey() != null) n.put("trade_key", t.tradeKey());
        if (t.tradeLabel() != null) n.put("trade_label", t.tradeLabel());
        putNum(n, "mm_rate", t.mmRate());
        putNum(n, "qty_done", t.qtyDone());
        putNum(n, "budgeted_man_days", t.budgetedManDays());
        putNum(n, "actual_man_days", t.actualManDays());
        putNum(n, "actual_days_on_hidden_sides", t.actualDaysOnHiddenSides());
        putNum(n, "actual_days_untracked", t.actualDaysUntracked());
        putNum(n, "utilization_pct", t.utilizationPct());
        putNum(n, "cost_implication", t.costImplication());
        if (t.normSource() != null) n.put("norm_source", t.normSource());
        if (t.buckets() != null) n.set("buckets", renderPeriodMetricsBuckets(t.buckets()));
        return n;
    }

    private ObjectNode renderEquipmentRollup(EquipmentRollup e) {
        ObjectNode n = mapper.createObjectNode();
        if (e.equipmentKey() != null) n.put("equipment_key", e.equipmentKey());
        if (e.equipmentLabel() != null) n.put("equipment_label", e.equipmentLabel());
        putNum(n, "hour_rate", e.hourRate());
        putNum(n, "qty_done", e.qtyDone());
        putNum(n, "budgeted_days", e.budgetedDays());
        putNum(n, "actual_days", e.actualDays());
        putNum(n, "actual_days_on_hidden_sides", e.actualDaysOnHiddenSides());
        putNum(n, "actual_days_untracked", e.actualDaysUntracked());
        putNum(n, "utilization_pct", e.utilizationPct());
        putNum(n, "cost_implication", e.costImplication());
        if (e.normSource() != null) n.put("norm_source", e.normSource());
        if (e.buckets() != null) n.set("buckets", renderPeriodMetricsBuckets(e.buckets()));
        return n;
    }

    private ObjectNode renderPeriodMetricsBuckets(PeriodMetricsBuckets b) {
        ObjectNode n = mapper.createObjectNode();
        if (b.day() != null) n.set("day", renderPeriodMetrics(b.day()));
        if (b.calendarMonth() != null) n.set("calendar_month", renderPeriodMetrics(b.calendarMonth()));
        if (b.cumulative() != null) n.set("cumulative", renderPeriodMetrics(b.cumulative()));
        return n;
    }

    private ObjectNode renderPeriodMetrics(PeriodMetrics p) {
        ObjectNode n = mapper.createObjectNode();
        putNum(n, "qty", p.qty());
        putNum(n, "budgeted_days", p.budgetedDays());
        putNum(n, "actual_days", p.actualDays());
        putNum(n, "actual_days_on_hidden_sides", p.actualDaysOnHiddenSides());
        putNum(n, "actual_days_untracked", p.actualDaysUntracked());
        putNum(n, "utilization_pct", p.utilizationPct());
        putNum(n, "cost_implication", p.costImplication());
        return n;
    }

    private ArrayNode renderHiddenNotes(List<HiddenSideNote> notes) {
        ArrayNode arr = mapper.createArrayNode();
        if (notes == null) return arr;
        for (HiddenSideNote h : notes) {
            ObjectNode hn = mapper.createObjectNode();
            if (h.activityId() != null) hn.put("activity_id", h.activityId().toString());
            if (h.workActivityName() != null) hn.put("work_activity", h.workActivityName());
            if (h.governingSide() != null) hn.put("governing_side", h.governingSide());
            if (h.mode() != null) hn.put("mode", h.mode());
            arr.add(hn);
        }
        return arr;
    }

    private ObjectNode renderActivity(ActivityDrillDown a) {
        ObjectNode n = mapper.createObjectNode();
        if (a.activityId() != null) n.put("activity_id", a.activityId().toString());
        if (a.activityCode() != null) n.put("activity_code", a.activityCode());
        if (a.activityName() != null) n.put("activity_name", a.activityName());
        if (a.unit() != null) n.put("unit", a.unit());
        // Legacy field name — actually cumulative-window. See DTO javadoc.
        putNum(n, "qty_cumulative_window", a.qtyForMonth());
        putNum(n, "qty_for_day", a.qtyForDay());
        putNum(n, "qty_for_calendar_month", a.qtyForCalendarMonth());
        putNum(n, "sub_contractor_qty", a.subContractorQty());
        if (a.subContractorQty() != null && a.qtyForMonth() != null
                && a.subContractorQty().signum() > 0) {
            BigDecimal effective = a.qtyForMonth().subtract(a.subContractorQty());
            if (effective.signum() < 0) effective = BigDecimal.ZERO;
            n.put("effective_company_qty", effective);
        }
        ArrayNode resources = mapper.createArrayNode();
        if (a.resources() != null) {
            for (ResourceLine rl : a.resources()) resources.add(renderResourceLine(rl));
        }
        n.set("resources", resources);
        if (a.remarks() != null) n.put("remarks", a.remarks());
        return n;
    }

    private ObjectNode renderResourceLine(ResourceLine rl) {
        ObjectNode n = mapper.createObjectNode();
        if (rl.kind() != null) n.put("kind", rl.kind());
        if (rl.resourceKey() != null) n.put("resource_key", rl.resourceKey());
        if (rl.resourceLabel() != null) n.put("resource_label", rl.resourceLabel());
        if (rl.norms() != null) n.set("norms", renderNorms(rl.norms()));
        // Legacy fields — actually cumulative-window. See DTO javadoc.
        if (rl.planMonth() != null)
            n.set("plan_cumulative_window", renderPlannedActuals(rl.planMonth()));
        if (rl.actualMonth() != null)
            n.set("actual_cumulative_window", renderPlannedActuals(rl.actualMonth()));
        if (rl.planBuckets() != null)
            n.set("plan_buckets", renderPlannedActualsBuckets(rl.planBuckets()));
        if (rl.actualBuckets() != null)
            n.set("actual_buckets", renderPlannedActualsBuckets(rl.actualBuckets()));
        return n;
    }

    private ObjectNode renderPlannedActualsBuckets(PlannedActualsBuckets b) {
        ObjectNode n = mapper.createObjectNode();
        if (b.day() != null) n.set("day", renderPlannedActuals(b.day()));
        if (b.calendarMonth() != null) n.set("calendar_month", renderPlannedActuals(b.calendarMonth()));
        if (b.cumulative() != null) n.set("cumulative", renderPlannedActuals(b.cumulative()));
        return n;
    }

    private ObjectNode renderNorms(ProductivityNorms p) {
        ObjectNode n = mapper.createObjectNode();
        putNum(n, "budget", p.budget());
        putNum(n, "projection", p.projection());
        putNum(n, "actuals_ftm", p.actualsFtm());
        if (p.normSource() != null) n.put("norm_source", p.normSource());
        return n;
    }

    private ObjectNode renderPlannedActuals(PlannedActuals p) {
        ObjectNode n = mapper.createObjectNode();
        putNum(n, "qty", p.qty());
        putNum(n, "budget_days", p.budgetDays());
        putNum(n, "days", p.days());
        putNum(n, "utilization_pct", p.utilizationPct());
        return n;
    }

    private ObjectNode renderTradeDelta(TradeDelta d) {
        ObjectNode n = mapper.createObjectNode();
        if (d.tradeKey() != null) n.put("trade_key", d.tradeKey());
        if (d.tradeLabel() != null) n.put("trade_label", d.tradeLabel());
        if (d.bestSupervisorId() != null)
            n.put("best_supervisor_id", d.bestSupervisorId().toString());
        putNum(n, "best_utilization_pct", d.bestUtilizationPct());
        ObjectNode by = mapper.createObjectNode();
        if (d.bySupervisor() != null) {
            for (Map.Entry<UUID, TradeRollup> e : d.bySupervisor().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    by.set(e.getKey().toString(), renderTradeRollup(e.getValue()));
                }
            }
        }
        n.set("by_supervisor", by);
        return n;
    }

    private ObjectNode renderEquipmentDelta(EquipmentDelta d) {
        ObjectNode n = mapper.createObjectNode();
        if (d.equipmentKey() != null) n.put("equipment_key", d.equipmentKey());
        if (d.equipmentLabel() != null) n.put("equipment_label", d.equipmentLabel());
        if (d.bestSupervisorId() != null)
            n.put("best_supervisor_id", d.bestSupervisorId().toString());
        putNum(n, "best_utilization_pct", d.bestUtilizationPct());
        ObjectNode by = mapper.createObjectNode();
        if (d.bySupervisor() != null) {
            for (Map.Entry<UUID, EquipmentRollup> e : d.bySupervisor().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    by.set(e.getKey().toString(), renderEquipmentRollup(e.getValue()));
                }
            }
        }
        n.set("by_supervisor", by);
        return n;
    }

    private static void putNum(ObjectNode node, String field, BigDecimal v) {
        if (v != null) node.put(field, v);
    }

    private String buildSummary(int requested, int reports, int tradeDeltas,
                                 int equipmentDeltas, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("Supervisor performance ")
                .append(from).append("..").append(to).append(": ")
                .append(reports).append(" / ").append(requested).append(" supervisor")
                .append(requested == 1 ? "" : "s");
        if (tradeDeltas > 0 || equipmentDeltas > 0) {
            sb.append("; ").append(tradeDeltas).append(" trade delta")
                    .append(tradeDeltas == 1 ? "" : "s")
                    .append(", ").append(equipmentDeltas).append(" equipment delta")
                    .append(equipmentDeltas == 1 ? "" : "s")
                    .append(" with best-per-row");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────── input parsing

    private List<UUID> readUuidArray(JsonNode input, String field) {
        if (input == null) return Collections.emptyList();
        JsonNode arr = input.path(field);
        if (arr == null || arr.isMissingNode() || arr.isNull()) return Collections.emptyList();
        List<UUID> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode el : arr) {
                String s = el == null ? null : el.asText(null);
                UUID u = parseUuid(s);
                if (u != null) out.add(u);
            }
        } else if (arr.isTextual()) {
            // Accept comma-separated string fallback for LLMs that produce a string.
            for (String part : arr.asText("").split(",")) {
                UUID u = parseUuid(part);
                if (u != null) out.add(u);
            }
        }
        return out;
    }

    private ObjectNode str(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    private static String text(JsonNode in, String field) {
        if (in == null) return null;
        JsonNode n = in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parseDate(JsonNode in, String field, LocalDate fallback) {
        String s = text(in, field);
        if (s == null) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
