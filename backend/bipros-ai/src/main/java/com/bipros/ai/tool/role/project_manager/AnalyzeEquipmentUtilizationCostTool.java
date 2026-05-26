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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per equipment row, computes utilization percentage (active hours / available hours) and
 * cost per active hour. Output is grouped by ownership (OWNED vs HIRED) with an ownership-level
 * summary comparing average cost across ownership categories.
 *
 * <p>Data source:
 * <ul>
 *   <li>{@code bipros_analytics.fact_dpr_equipment_daily} — {@code working_hours} (active),
 *       {@code idle_hours}, {@code breakdown_hours}, {@code ownership}, {@code equipment_type},
 *       {@code fleet_no}, {@code equipment_row_id} per DPR submission.</li>
 * </ul>
 *
 * <p>Hourly rate is not stored in the ClickHouse fact table nor the {@code ResourceEquipmentDetails}
 * JPA entity. Until a {@code hire_rate} or {@code internal_rate} column is added to the
 * equipment dimension or the fact table, {@code cost_per_active_hour} will be {@code null}
 * (surfaced as {@code 0} via ClickHouse {@code toFloat64} on a missing column and reported as
 * {@code "data_unavailable"} in the summary note).
 *
 * <p>Formula:
 * <pre>
 *   available_hours   = working_hours + idle_hours + breakdown_hours
 *   utilization_pct   = 100 * working_hours / nullIf(available_hours, 0)
 *   cost_per_active_hour = null  (hourly_rate not in schema)
 * </pre>
 *
 * <p>Ownership values from DPR data are free-text strings passed through from the DPR form.
 * When the value is blank or missing it is normalised to {@code "UNKNOWN"}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeEquipmentUtilizationCostTool extends ProjectScopedTool {

    private final ClickHouseTemplate clickHouse;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_equipment_utilization_cost";
    }

    @Override
    public String description() {
        return "Project-Manager-framed per-equipment hour-utilization + cost-per-active-hour view, "
                + "grouped by ownership (OWNED vs HIRED/RENTED). Reads ClickHouse "
                + "fact_dpr_equipment_daily. Note: hourly_rate is not yet stored in the fact table, "
                + "so cost_per_active_hour returns null. Blank/missing ownership values are "
                + "normalised to UNKNOWN. "
                + "**PREFER `get_capacity_utilization` (with norm_type=EQUIPMENT) for any per-role "
                + "or per-activity efficiency question — that tool runs the per-DPR allocator and "
                + "applies hidden-side handling (SERIES / SUBSTITUTE), and is sub-contractor-aware. "
                + "Use THIS tool only when the user explicitly asks for ownership-grouped hour "
                + "utilization or cost-per-active-hour (an ownership-and-cost view that the "
                + "canonical efficiency tool does not provide).**";
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
        return Set.of("PROJECT_MANAGER", "PORTFOLIO_MANAGER", "COST_CONTROLLER", "RESOURCE_MANAGER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error(
                    "Pick a project first — equipment utilization analysis is per-project.");
        }

        LocalDate to   = parse(input.path("to").asText(null), LocalDate.now());
        LocalDate from = parse(input.path("from").asText(null), to.minusDays(30));

        /*
         * Aggregate per equipment_row_id across the date range.
         *
         * - working_hours  → active hours (equipment actively running)
         * - idle_hours     → present but not running
         * - breakdown_hours→ out-of-service
         * - available_hours = working + idle + breakdown
         * - utilization_pct = 100 * working / available (null when available=0)
         *
         * fleet_no and equipment_type are dimension labels from the DPR rows.
         * ownership is a free-text string from the DPR form (OWNED, HIRED, etc.).
         * hourly_rate is not stored; cost_per_active_hour is reported as null.
         */
        String sql = """
                SELECT
                    toString(equipment_row_id)              AS equipment_id,
                    any(equipment_type)                     AS equipment_name,
                    if(any(ownership) = '', 'UNKNOWN', any(ownership)) AS ownership,
                    round(sum(working_hours), 2)            AS active_hours,
                    round(sum(idle_hours), 2)               AS idle_hours,
                    round(
                        100.0 * sum(working_hours)
                              / nullIf(sum(working_hours) + sum(idle_hours) + sum(breakdown_hours), 0),
                        2
                    )                                       AS utilization_pct,
                    null                                    AS cost_per_active_hour
                FROM bipros_analytics.fact_dpr_equipment_daily
                WHERE project_id = :pid
                  AND report_date BETWEEN :from AND :to
                GROUP BY equipment_row_id
                ORDER BY utilization_pct DESC NULLS LAST
                LIMIT 500
                """;

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("pid",  ctx.projectId());
        params.put("from", from);
        params.put("to",   to);

        List<Map<String, Object>> rows;
        try {
            rows = clickHouse.queryForList(sql, params);
        } catch (Exception e) {
            log.warn("analyze_equipment_utilization_cost: ClickHouse query failed: {}", e.getMessage());
            return dataUnavailable(
                    "fact_dpr_equipment_daily is not yet populated or accessible for this project.",
                    "Submit at least one DPR with equipment entries to populate the fact table.",
                    "analyze_labour_cost_per_unit (for labour utilisation data)");
        }

        if (rows.isEmpty()) {
            return dataUnavailable(
                    "No equipment log rows for project " + ctx.projectId()
                            + " between " + from + " and " + to + ".",
                    "Confirm DPR submissions include equipment line items for this date range.",
                    "analyze_labour_cost_per_unit to inspect labour data for the same period");
        }

        // Build the detail rows array
        ArrayNode arr = objectMapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            r.forEach((k, v) -> o.set(k, objectMapper.valueToTree(v)));
            arr.add(o);
        }

        // Build ownership-level summary
        ObjectNode summary = buildOwnershipSummary(rows);

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        ArrayNode cols = objectMapper.createArrayNode();
        for (String c : new String[]{
                "equipment_id", "equipment_name", "ownership",
                "active_hours", "idle_hours", "utilization_pct", "cost_per_active_hour"}) {
            cols.add(c);
        }
        wrapper.set("columns", cols);
        wrapper.set("summary", summary);
        wrapper.put("cost_per_active_hour_note",
                "hourly_rate is not stored in fact_dpr_equipment_daily — "
                        + "cost_per_active_hour is null until an equipment rate dimension is added.");

        return ToolResult.ok(
                "Equipment utilization from " + from + " to " + to + " — "
                        + rows.size() + " equipment unit(s), highest utilization first. "
                        + "cost_per_active_hour is null (hourly rate not in schema).",
                wrapper);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Groups rows by ownership and computes average utilization and average cost
     * (null for cost since hourly_rate is not available).
     */
    private ObjectNode buildOwnershipSummary(List<Map<String, Object>> rows) {
        // ownership → {count, totalUtilization}
        Map<String, double[]> buckets = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String ownership = asString(r.get("ownership"), "UNKNOWN");
            double util = asDouble(r.get("utilization_pct"));
            buckets.computeIfAbsent(ownership, k -> new double[]{0, 0});
            buckets.get(ownership)[0] += 1;
            buckets.get(ownership)[1] += util;
        }

        ObjectNode summary = objectMapper.createObjectNode();
        List<ObjectNode> byOwnership = new ArrayList<>();
        for (Map.Entry<String, double[]> e : buckets.entrySet()) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("ownership", e.getKey());
            entry.put("equipment_count", (int) e.getValue()[0]);
            entry.put("avg_utilization_pct",
                    Math.round(e.getValue()[1] / e.getValue()[0] * 100.0) / 100.0);
            entry.putNull("avg_cost_per_active_hour"); // not in schema
            byOwnership.add(entry);
        }
        ArrayNode byOwnershipArr = objectMapper.createArrayNode();
        byOwnership.forEach(byOwnershipArr::add);
        summary.set("by_ownership", byOwnershipArr);
        summary.put("avg_cost_note", "hourly_rate not in schema — avg_cost_per_active_hour is null");
        return summary;
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

    private String asString(Object val, String fallback) {
        if (val == null) return fallback;
        String s = val.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private double asDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
    }
}
