package com.bipros.ai.tool.role.project_manager;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.analytics.store.ClickHouseTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per activity, computes actual labour cost per unit installed and compares it to the budgeted
 * unit rate (when available). Rows are sorted by {@code delta_pct} descending so overspend
 * appears first.
 *
 * <p>Data sources:
 * <ul>
 *   <li>{@code bipros_analytics.fact_cost_daily} — {@code labor_cost} column is the actual
 *       labour cost rolled up per (project, activity, date).</li>
 *   <li>{@code bipros_analytics.fact_dpr_logs} — {@code qty_executed} is the physical quantity
 *       installed per (project, activity, date) from DPR submissions.</li>
 *   <li>{@code bipros_analytics.dim_activity} — provides {@code code} (human-readable activity
 *       code) and {@code uom} (unit of measure for the activity).</li>
 * </ul>
 *
 * <p>Budget unit rate is not available at activity level in the current schema — there is no
 * {@code budget_unit_rate} column on dim_activity or fact_cost_daily. When unavailable,
 * {@code budget_per_unit} is null and {@code delta_pct} is null for that row. The actual
 * cost-per-unit figure is still surfaced as a standalone metric.
 *
 * <p>Formula:
 * <pre>
 *   actual_per_unit = sum(labor_cost) / nullIf(sum(qty_executed), 0)
 *   budget_per_unit = null  (no budget unit rate in schema yet)
 *   delta_pct       = null  (cannot compute without budget_per_unit)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeLabourCostPerUnitTool extends ProjectScopedTool {

    private final ClickHouseTemplate clickHouse;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_labour_cost_per_unit";
    }

    @Override
    public String description() {
        return "Per activity, compute actual labour cost per unit installed and compare to "
                + "budgeted unit rate. Rows are sorted by delta_pct descending (overspend first). "
                + "Uses fact_cost_daily (labor_cost) and fact_dpr_logs (qty_executed), joined "
                + "to dim_activity for activity code and unit. Budget unit rate is not currently "
                + "stored at activity level; budget_per_unit and delta_pct will be null until "
                + "that data is captured. "
                + "SUB-CONTRACTOR NOTE: the qty_executed denominator is GROSS workdone (includes "
                + "sub-contractor qty). For a true company-resource labour-cost-per-unit, the "
                + "denominator should be effective_company_qty (= gross − sub_contractor_qty); "
                + "until that variant is computed in the warehouse, mention this caveat in the "
                + "answer when sub-contractor is present on the activity. Use "
                + "get_subcontractor_kpis to surface the SC qty/cost separately.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("from", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date")
                .put("description", "Start date (inclusive). Defaults to 30 days before 'to'."));
        props.set("to", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date")
                .put("description", "End date (inclusive). Defaults to today."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of("PROJECT_MANAGER", "PORTFOLIO_MANAGER", "COST_CONTROLLER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("Pick a project first — labour cost per unit analysis is per-project.");
        }

        LocalDate to = parse(input.path("to").asText(null), LocalDate.now());
        LocalDate from = parse(input.path("from").asText(null), to.minusDays(30));

        /*
         * Strategy:
         *   1. Aggregate labor_cost from fact_cost_daily per (project_id, activity_id).
         *   2. Aggregate qty_executed from fact_dpr_logs per (project_id, activity_id).
         *   3. LEFT JOIN dim_activity for code and uom.
         *   4. Compute actual_per_unit = total_cost / total_qty.
         *   5. budget_per_unit is null (not in schema); delta_pct is null.
         *   6. Sort by delta_pct DESC NULLS LAST (nulls go to the bottom when budget unavailable).
         *
         * Note: fact_cost_daily.date and fact_dpr_logs.report_date are used for filtering.
         * Both tables use the same project_id + activity_id join key.
         */
        String sql = """
                WITH cost_agg AS (
                    SELECT activity_id,
                           sum(labor_cost) AS total_labor_cost
                    FROM bipros_analytics.fact_cost_daily
                    WHERE project_id = :pid
                      AND date BETWEEN :from AND :to
                      AND labor_cost > 0
                    GROUP BY activity_id
                ),
                qty_agg AS (
                    SELECT activity_id,
                           sum(qty_executed) AS total_qty
                    FROM bipros_analytics.fact_dpr_logs
                    WHERE project_id = :pid
                      AND report_date BETWEEN :from AND :to
                    GROUP BY activity_id
                )
                SELECT any(a.code)                                                   AS activity_code,
                       round(toFloat64(c.total_labor_cost), 4)                       AS actual_cost,
                       round(coalesce(q.total_qty, 0), 4)                            AS qty_executed,
                       round(toFloat64(c.total_labor_cost)
                             / nullIf(coalesce(q.total_qty, 0), 0), 4)               AS actual_per_unit,
                       null                                                           AS budget_per_unit,
                       null                                                           AS delta_pct,
                       any(a.uom)                                                     AS unit
                FROM cost_agg c
                LEFT JOIN qty_agg q ON q.activity_id = c.activity_id
                LEFT JOIN bipros_analytics.dim_activity a
                  ON a.activity_id = c.activity_id
                 AND a.project_id  = :pid
                GROUP BY c.activity_id, c.total_labor_cost, q.total_qty
                ORDER BY actual_per_unit DESC NULLS LAST
                LIMIT 200
                """;

        Map<String, Object> params = new HashMap<>();
        params.put("pid", ctx.projectId());
        params.put("from", from);
        params.put("to", to);

        List<Map<String, Object>> rows;
        try {
            rows = clickHouse.queryForList(sql, params);
        } catch (Exception e) {
            log.warn("analyze_labour_cost_per_unit: ClickHouse query failed: {}", e.getMessage());
            return dataUnavailable(
                    "fact_cost_daily or fact_dpr_logs is not yet populated or accessible for this project.",
                    "Backfill fact_cost_daily from resource-assignment cost listeners and "
                            + "fact_dpr_logs from DPR submissions for this project.",
                    "analyze_labour_utilization (man-days vs planned head-count per contractor)");
        }

        if (rows.isEmpty()) {
            return dataUnavailable(
                    "No labour cost rows for project " + ctx.projectId()
                            + " between " + from + " and " + to + ".",
                    "Confirm DPR submissions and resource assignments with daily cost are present "
                            + "for this date range.",
                    "analyze_labour_utilization to inspect man-day data for the same period");
        }

        ArrayNode arr = objectMapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            r.forEach((k, v) -> o.set(k, objectMapper.valueToTree(v)));
            arr.add(o);
        }

        return ToolResult.table(
                "Labour cost per unit from " + from + " to " + to + " — "
                        + rows.size() + " activity(ies), highest actual-per-unit first. "
                        + "Note: budget_per_unit and delta_pct are null — no activity-level "
                        + "budget unit rate is captured in the current schema.",
                arr,
                new String[]{"activity_code", "actual_cost", "qty_executed",
                        "actual_per_unit", "budget_per_unit", "delta_pct", "unit"}
        );
    }

    private ToolResult dataUnavailable(String reason, String whatNeeded, String closest) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "data_unavailable");
        payload.put("reason", reason);
        payload.put("what_would_be_needed", whatNeeded);
        payload.put("closest_available", closest);
        return ToolResult.ok("Data not yet captured: " + reason, payload);
    }

    private LocalDate parse(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return fallback;
        }
    }
}
