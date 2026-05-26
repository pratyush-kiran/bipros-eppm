package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.analytics.store.ClickHouseTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeCostTool extends ProjectScopedTool {

    private final ClickHouseTemplate clickHouse;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_cost";
    }

    @Override
    public String description() {
        return "Analyze cost variance. With a current project_id, breaks down by WBS / cost_account / "
                + "activity. With no current project_id (general mode), aggregates per project across the "
                + "user's scope so the agent can compare projects. "
                + "NOTE: this tool queries the analytics warehouse (fact_cost_daily) which carries no "
                + "rate-basis or pool-override metadata. For per-resource rate questions or 'is this "
                + "rate the project override?' prefer list_activity_resources / find_resource_deployment "
                + "/ get_resource_profile (live JPA tools that emit effective_rate + override_applied). "
                + "SUB-CONTRACTOR NOTE: actual_cost in fact_cost_daily already includes sub-contractor "
                + "cost alongside manpower/equipment/material — do not add SC cost on top. To isolate "
                + "sub-contractor cost vs company cost, call get_subcontractor_kpis. For BOQ-item cost "
                + "variance specifically, prefer query_boq — its cost_variance comes directly from "
                + "BoqItem.costVariance = actualAmount − (qtyExecutedToDate × BUDGETED rate). Do NOT "
                + "use the BOQ/client rate and do NOT recompute client-side.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("period", objectMapper.createObjectNode().put("type", "string").put("description", "month identifier like 2024-01 or 'current'"));
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("wbs");
        enumValues.add("cost_account");
        enumValues.add("activity");
        ObjectNode groupByNode = objectMapper.createObjectNode();
        groupByNode.put("type", "string");
        groupByNode.set("enum", enumValues);
        props.set("group_by", groupByNode);
        schema.set("properties", props);
        return schema;
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        String period = input.path("period").asText("current");
        String groupBy = input.path("group_by").asText("wbs");

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now();
        if (!"current".equals(period)) {
            try {
                from = LocalDate.parse(period + "-01");
                to = from.withDayOfMonth(from.lengthOfMonth());
            } catch (Exception ignored) {
            }
        }

        boolean crossProject = ctx.projectId() == null;
        String groupColumn = crossProject ? "project_id" : switch (groupBy) {
            case "cost_account" -> "cost_account_id";
            case "activity" -> "activity_id";
            default -> "wbs_id";
        };

        StringBuilder sql = new StringBuilder("""
            SELECT %s as group_key,
                   sum(total_actual) as actual,
                   sum(total_planned) as planned,
                   sum(total_earned) as earned,
                   sum(total_actual) - sum(total_planned) as variance
            FROM bipros_analytics.fact_cost_daily
            WHERE date BETWEEN :from AND :to
            """.formatted(groupColumn));

        Map<String, Object> params = new HashMap<>();
        params.put("from", from);
        params.put("to", to);

        if (!crossProject) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", ctx.projectId());
        } else {
            List<UUID> scope = ctx.scopedProjectIds();
            if (scope != null && !scope.isEmpty()) {
                String inList = scope.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
                sql.append(" AND project_id IN (").append(inList).append(")");
            }
        }
        sql.append(" GROUP BY group_key ORDER BY variance DESC LIMIT 20");

        List<Map<String, Object>> rows = clickHouse.queryForList(sql.toString(), params);
        ArrayNode arr = objectMapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            r.forEach((k, v) -> o.set(k, objectMapper.valueToTree(v)));
            arr.add(o);
        }

        String summary = crossProject
                ? "Cost variance per project across portfolio (" + from + ".." + to + ")"
                : "Cost variance by " + groupBy;
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        ArrayNode cols = objectMapper.createArrayNode();
        for (String c : new String[]{"group_key", "actual", "planned", "earned", "variance"}) cols.add(c);
        wrapper.set("columns", cols);
        ArrayNode notes = objectMapper.createArrayNode();
        notes.add("warehouse_snapshot_basis_blind");
        wrapper.set("formula_overrides", notes);
        return ToolResult.ok(summary, wrapper);
    }

    @Override
    public java.util.Set<String> allowedRoles() {
        return java.util.Set.of(
                "PROJECT_MANAGER",
                "PORTFOLIO_MANAGER",
                "RISK_MANAGER",
                "COST_CONTROLLER",
                "EXECUTIVE_VIEWER"
        );
    }
}
