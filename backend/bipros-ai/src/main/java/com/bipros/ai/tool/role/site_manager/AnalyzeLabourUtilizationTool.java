package com.bipros.ai.tool.role.site_manager;

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
 * Reports labour utilization (actual man-days / planned man-days) per contractor and skill
 * category for a single project over a date range.
 *
 * <p>The underlying fact table is {@code bipros_analytics.fact_labour_daily}, which is populated
 * by the ETL pipeline whenever a Labour Return is submitted or a Project Labour Deployment changes.
 * Columns used:
 * <ul>
 *   <li>{@code contractor_name} — groups rows by contractor</li>
 *   <li>{@code skill_category} — sub-groups within each contractor</li>
 *   <li>{@code man_days} — actual man-days delivered (from labour return)</li>
 *   <li>{@code planned_head_count} — planned head count per day (proxy for planned deployment)</li>
 * </ul>
 *
 * <p>Rows are ordered ascending by utilization so the Site Manager sees the most
 * under-deployed or absent crews at the top.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeLabourUtilizationTool extends ProjectScopedTool {

    private final ClickHouseTemplate clickHouse;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_labour_utilization";
    }

    @Override
    public String description() {
        return "Site-Manager-framed labour deployment view (actual man-days vs planned head-count) "
                + "per contractor and skill category. Defaults to last 7 days. Reads from the "
                + "ClickHouse fact_labour_daily fact table populated when Labour Returns are submitted. "
                + "**PREFER `get_capacity_utilization` when the user asks about role-level efficiency, "
                + "productivity vs norm, the per-DPR allocator output, hidden-side (SERIES/SUBSTITUTE) "
                + "handling, or sub-contractor-netted workdone — this tool's man-days denominator does "
                + "NOT apply the per-DPR allocator and does NOT net sub-contractor qty out, so its "
                + "numbers can disagree with the canonical capacity-utilization report. Use THIS tool "
                + "only when the user explicitly asks for the contractor-level Labour Return deployment "
                + "view.**";
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
        return Set.of("SITE_MANAGER", "PROJECT_MANAGER", "RESOURCE_MANAGER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("Pick a project first — labour utilization is per-project.");
        }

        LocalDate to = parse(input.path("to").asText(null), LocalDate.now());
        LocalDate from = parse(input.path("from").asText(null), to.minusDays(7));

        /*
         * fact_labour_daily real columns (from ETL INSERT):
         *   project_id, labour_return_id, deployment_id, designation_id,
         *   skill_category, contractor_name, contractor_org_id, wbs_id, site_location,
         *   date, head_count, man_days, planned_head_count, daily_rate, daily_cost,
         *   source, event_ts, _version
         *
         * We group by contractor_name + skill_category and compute:
         *   - actual_man_days  = sum(man_days)
         *   - planned_man_days = sum(planned_head_count)  [planned head-count × 1 day each row]
         *   - utilization_pct  = 100 × actual / planned
         */
        String sql = """
                SELECT contractor_name                                           AS crew_id,
                       any(skill_category)                                       AS crew_name,
                       sum(man_days)                                             AS actual_hours,
                       toFloat64(sum(planned_head_count))                        AS planned_hours,
                       round(100.0 * sum(man_days)
                             / nullIf(toFloat64(sum(planned_head_count)), 0), 1) AS utilization_pct
                FROM bipros_analytics.fact_labour_daily
                WHERE project_id = :pid
                  AND date BETWEEN :from AND :to
                GROUP BY contractor_name
                ORDER BY utilization_pct ASC NULLS FIRST
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
            log.warn("analyze_labour_utilization: ClickHouse query failed: {}", e.getMessage());
            return dataUnavailable(
                    "fact_labour_daily is not yet populated or accessible for this project.",
                    "Backfill fact_labour_daily from Labour Return submissions for this project.",
                    "query_dpr (raw daily progress with manpower lines)");
        }

        if (rows.isEmpty()) {
            return dataUnavailable(
                    "No labour rows for project " + ctx.projectId() + " between " + from + " and " + to + ".",
                    "Confirm Labour Returns are being submitted with manpower entries for this period.",
                    "query_dpr to inspect raw DPR records");
        }

        ArrayNode arr = objectMapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            r.forEach((k, v) -> o.set(k, objectMapper.valueToTree(v)));
            arr.add(o);
        }

        return ToolResult.table(
                "Labour utilization from " + from + " to " + to + " — " + rows.size() + " contractor(s).",
                arr,
                new String[]{"crew_id", "crew_name", "actual_hours", "planned_hours", "utilization_pct"}
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
