package com.bipros.ai.tool.role.project_engineer;

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
 * Computes productivity factor (actual output per man-hour vs activity norm) per crew/activity,
 * sorted by variance ascending (worst-performing first).
 *
 * <p>Data source: {@code bipros_analytics.fact_resource_usage_daily}, which carries both
 * {@code productivity_actual} and {@code productivity_norm} alongside {@code qty_executed}
 * and {@code hours_worked}. Crew identity is approximated by the resource code/name
 * from {@code dim_resource}.
 *
 * <p>The {@code actual_per_hour} is computed as {@code sum(qty_executed) / sum(hours_worked)}.
 * {@code norm_per_hour} is taken as {@code avg(productivity_norm)} from the same rows.
 * {@code variance_pct} = {@code 100 * (actual_per_hour - norm_per_hour) / norm_per_hour}.
 * Rows are sorted {@code variance_pct ASC NULLS FIRST} so the most under-performing
 * crew/activity pairs surface at the top.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeProductivityFactorTool extends ProjectScopedTool {

    private final ClickHouseTemplate clickHouse;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_productivity_factor";
    }

    @Override
    public String description() {
        return "Project-Engineer-framed crew-level productivity-factor view (actual output per "
                + "man-hour vs activity norm) for a project + date range. Reads ClickHouse "
                + "fact_resource_usage_daily. Defaults to last 7 days. "
                + "**PREFER `get_capacity_utilization` for the authoritative answer — that tool runs "
                + "the per-DPR allocator (2026-05-22), nets sub-contractor qty out of workdone, and "
                + "applies SERIES/PARALLEL/SUBSTITUTE hiding. This tool's hour-based denominator does "
                + "NONE of that and can disagree with the canonical service. Use THIS tool only when "
                + "the user explicitly asks for the crew × activity per-man-hour breakdown.** "
                + "For sub-contractor productivity questions, use get_subcontractor_kpis.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("from", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date")
                .put("description", "Start date (inclusive). Defaults to 7 days before 'to'."));
        props.set("to", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date")
                .put("description", "End date (inclusive). Defaults to today."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of("PROJECT_ENGINEER", "PROJECT_MANAGER", "SITE_MANAGER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("Pick a project first — productivity factor analysis is per-project.");
        }

        LocalDate to = parse(input.path("to").asText(null), LocalDate.now());
        LocalDate from = parse(input.path("from").asText(null), to.minusDays(7));

        /*
         * fact_resource_usage_daily columns relevant here:
         *   project_id, activity_id, resource_id, resource_type,
         *   date, hours_worked, qty_executed, productivity_actual, productivity_norm
         *
         * dim_resource: resource_id, project_id, code, name
         * dim_activity: activity_id, project_id, code, name
         *
         * actual_per_hour  = sum(qty_executed) / nullIf(sum(hours_worked), 0)
         * norm_per_hour    = avg(productivity_norm)   [already stored as per-hour figure]
         * variance_pct     = 100 * (actual_per_hour - norm_per_hour) / nullIf(norm_per_hour, 0)
         */
        String sql = """
                SELECT r.resource_id                                                   AS crew_id,
                       any(r.name)                                                     AS crew_name,
                       any(a.code)                                                     AS activity_code,
                       round(sum(frd.qty_executed)
                             / nullIf(sum(frd.hours_worked), 0), 4)                    AS actual_per_hour,
                       round(avg(frd.productivity_norm), 4)                            AS norm_per_hour,
                       round(100.0 * (
                               sum(frd.qty_executed) / nullIf(sum(frd.hours_worked), 0)
                               - avg(frd.productivity_norm)
                           ) / nullIf(avg(frd.productivity_norm), 0), 2)               AS variance_pct
                FROM bipros_analytics.fact_resource_usage_daily frd
                JOIN bipros_analytics.dim_resource r
                  ON r.resource_id = frd.resource_id
                 AND r.project_id  = frd.project_id
                JOIN bipros_analytics.dim_activity a
                  ON a.activity_id = frd.activity_id
                 AND a.project_id  = frd.project_id
                WHERE frd.project_id = :pid
                  AND frd.date BETWEEN :from AND :to
                  AND frd.productivity_norm > 0
                  AND frd.hours_worked      > 0
                GROUP BY r.resource_id, frd.activity_id
                ORDER BY variance_pct ASC NULLS FIRST
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
            log.warn("analyze_productivity_factor: ClickHouse query failed: {}", e.getMessage());
            return dataUnavailable(
                    "fact_resource_usage_daily is not yet populated or accessible for this project.",
                    "Backfill fact_resource_usage_daily from DPR resource entries for this project.",
                    "compare_actual_vs_norm (JPA-backed productivity vs norm comparison)");
        }

        if (rows.isEmpty()) {
            return dataUnavailable(
                    "No resource-usage rows with a productivity norm for project "
                            + ctx.projectId() + " between " + from + " and " + to + ".",
                    "Confirm DPR resource entries are submitted with productivity norms set on activities.",
                    "compare_actual_vs_norm to inspect per-day (activity, resource) rows");
        }

        ArrayNode arr = objectMapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            r.forEach((k, v) -> o.set(k, objectMapper.valueToTree(v)));
            arr.add(o);
        }

        return ToolResult.table(
                "Productivity factor from " + from + " to " + to + " — "
                        + rows.size() + " crew/activity pair(s), worst-performing first.",
                arr,
                new String[]{"crew_id", "crew_name", "activity_code",
                        "actual_per_hour", "norm_per_hour", "variance_pct"}
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
