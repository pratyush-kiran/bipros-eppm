package com.bipros.api.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.api.service.SubContractorKpiService;
import com.bipros.api.service.SubContractorKpiService.SubContractorKpiResponse;
import com.bipros.api.service.SubContractorKpiService.SubContractorWorkTypeRow;
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
import java.util.UUID;

/**
 * Wraps {@link SubContractorKpiService} so the AI can answer sub-contractor performance
 * questions ("which sub-contractors are under-performing", "what is Apex's cost variance",
 * "show me the productivity factor per work-type") without re-implementing the per-(SC,
 * work-type) aggregation in SQL. Lives in {@code bipros-api} alongside the service so the
 * existing tool pattern (KPI service + AI tool in the same module) is preserved.
 *
 * <p>Each row in the response carries: SC code + name, work-type + unit + rate, norm/day,
 * planned vs actual qty, productivity_factor, planned vs actual cost, cost_variance, CPI.
 * Top-N convenience views are pre-computed by the service (bottomProductivity, topByCost,
 * bottomOutputAchievement) and serialised straight through.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSubcontractorKpisTool implements Tool {

    private final SubContractorKpiService service;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_subcontractor_kpis";
    }

    @Override
    public String description() {
        return "Sub-contractor KPIs — planned vs actual qty, cost variance, CPI, and "
                + "productivity factor (actual qty per day ÷ master norm) per (sub-contractor, "
                + "work-type) pair for a project + date window. Use this tool whenever the "
                + "question involves a sub-contractor or contractor's performance, "
                + "under-performance, cost overrun, qty completion, productivity, or output "
                + "achievement. Sub-contractor work is DISTINCT from company manpower / "
                + "equipment / material — the canonical capacity tools already net SC qty out "
                + "of workdone before computing role efficiency, so SC analysis lives in this "
                + "dedicated tool. "
                + "Returns: totals (planned_qty, actual_qty, qty_completion_pct, avg "
                + "productivity_factor, planned_cost, actual_cost, cost_variance, CPI, "
                + "days_worked, under_performing_count, unmatched_dpr_rows), "
                + "per_sc_work_type[] (one row per SC × work-type with SC code + name, "
                + "work_type, unit, rate, norm_per_day, planned/actual qty + cost, "
                + "productivity_factor, cost_variance, CPI, qty_completion_pct), and three "
                + "convenience top-N lists: bottom_productivity (lowest productivity factor), "
                + "top_by_cost (highest actual_cost), bottom_output_achievement (lowest "
                + "qty_completion_pct). "
                + "Inputs: from_date (default = first day of current month), to_date "
                + "(default = today). Project is taken from context. "
                + "RESPONSE FORMAT: lead with SC code + name + work-type, then qty (actual / "
                + "planned), then productivity_factor and cost_variance. Example: \"Apex "
                + "Infrastructure (SUB-INFRA-001 · Asphalt Laying): 80 / 500 Tonne done "
                + "(16.0% completion), productivity factor 0.41, cost variance ₹18,900 "
                + "favourable.\" "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("from_date", str("ISO date (yyyy-MM-dd). Default: first day of current month."));
        props.set("to_date", str("ISO date (yyyy-MM-dd). Default: today."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("get_subcontractor_kpis requires a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        LocalDate to = parseDate(input, "to_date", LocalDate.now());
        LocalDate from = parseDate(input, "from_date", to.withDayOfMonth(1));
        if (from.isAfter(to)) {
            LocalDate t = from; from = to; to = t;
        }

        try {
            SubContractorKpiResponse rpt = service.compute(projectId, from, to);
            ObjectNode out = renderResponse(rpt);
            return ToolResult.ok(buildSummary(rpt), out);
        } catch (Exception e) {
            log.warn("get_subcontractor_kpis failed", e);
            return ToolResult.error(
                    "Failed to compute sub-contractor KPIs: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────── rendering

    private ObjectNode renderResponse(SubContractorKpiResponse r) {
        ObjectNode n = mapper.createObjectNode();
        if (r.projectId() != null) n.put("project_id", r.projectId().toString());
        if (r.from() != null) n.put("from_date", r.from().toString());
        if (r.to() != null) n.put("to_date", r.to().toString());

        ObjectNode totals = mapper.createObjectNode();
        totals.put("active_sub_contractors", r.activeSubContractors());
        totals.put("work_types_tracked", r.workTypesTracked());
        putNum(totals, "total_planned_qty", r.totalPlannedQty());
        putNum(totals, "total_actual_qty", r.totalActualQty());
        putNum(totals, "qty_completion_pct", r.quantityCompletionPct());
        putNum(totals, "avg_productivity_factor", r.avgProductivityFactor());
        putNum(totals, "total_planned_cost", r.totalPlannedCost());
        putNum(totals, "total_actual_cost", r.totalActualCost());
        putNum(totals, "total_cost_variance", r.costVariance());
        putNum(totals, "total_cost_performance_index", r.costPerformanceIndex());
        totals.put("days_worked", r.daysWorked());
        if (r.impliedPlannedDays() != null) totals.put("implied_planned_days", r.impliedPlannedDays());
        totals.put("under_performing_count", r.underPerformingCount());
        totals.put("unmatched_dpr_rows", r.unmatchedDprRows());
        n.set("totals", totals);

        n.set("per_sc_work_type", renderRows(r.perScWorkType()));
        n.set("bottom_productivity", renderRows(r.bottomProductivity()));
        n.set("top_by_cost", renderRows(r.topByCost()));
        n.set("bottom_output_achievement", renderRows(r.bottomOutputAchievement()));
        return n;
    }

    private ArrayNode renderRows(java.util.List<SubContractorWorkTypeRow> rows) {
        ArrayNode arr = mapper.createArrayNode();
        if (rows == null) return arr;
        for (SubContractorWorkTypeRow row : rows) arr.add(renderRow(row));
        return arr;
    }

    private ObjectNode renderRow(SubContractorWorkTypeRow row) {
        ObjectNode n = mapper.createObjectNode();
        if (row.scMasterId() != null) n.put("sc_master_id", row.scMasterId().toString());
        if (row.scCode() != null) n.put("sc_code", row.scCode());
        if (row.scName() != null) n.put("sc_name", row.scName());
        if (row.scWorkTypeId() != null) n.put("sc_work_type_id", row.scWorkTypeId().toString());
        if (row.workTypeName() != null) n.put("work_type", row.workTypeName());
        if (row.unit() != null) n.put("unit", row.unit());
        putNum(n, "rate_per_unit", row.ratePerUnit());
        putNum(n, "norm_per_day", row.normPerDay());
        putNum(n, "planned_qty", row.plannedQty());
        putNum(n, "actual_qty", row.actualQty());
        n.put("distinct_days", row.distinctDays());
        putNum(n, "avg_qty_per_day", row.avgQtyPerDay());
        putNum(n, "productivity_factor", row.productivityFactor());
        putNum(n, "planned_cost", row.plannedCost());
        putNum(n, "actual_cost", row.actualCost());
        putNum(n, "cost_variance", row.costVariance());
        putNum(n, "cost_performance_index", row.costPerformanceIndex());
        putNum(n, "qty_completion_pct", row.qtyCompletionPct());
        return n;
    }

    private static void putNum(ObjectNode node, String field, BigDecimal v) {
        if (v != null) node.put(field, v);
    }

    private String buildSummary(SubContractorKpiResponse r) {
        StringBuilder sb = new StringBuilder("Sub-contractor KPIs ")
                .append(r.from()).append("..").append(r.to()).append(": ")
                .append(r.activeSubContractors()).append(" SC, ")
                .append(r.workTypesTracked()).append(" work-types");
        if (r.underPerformingCount() > 0) {
            sb.append(", ").append(r.underPerformingCount())
                    .append(" under-performing (productivity factor < 0.8)");
        }
        if (r.unmatchedDprRows() > 0) {
            sb.append(", ").append(r.unmatchedDprRows())
                    .append(" orphan DPR rows (no assignment FK)");
        }
        if (r.quantityCompletionPct() != null) {
            sb.append("; total qty completion ").append(r.quantityCompletionPct()).append("%");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────── helpers

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
}
