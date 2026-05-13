package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.analytics.query.ClickHouseQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryClickHouseTool extends ProjectScopedTool {

    private final ClickHouseQueryService queryService;
    private final SchemaCatalog schemaCatalog;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "query_clickhouse";
    }

    @Override
    public String description() {
        return "Use this ONLY when no specialised tool exists. For resources / cost / DPR / "
                + "assignment / issue questions on a single project, prefer the live JPA tools "
                + "(find_resource_deployment, list_activity_resources, "
                + "summarize_activity_resources, get_resource_profile, cost_breakdown, "
                + "query_dpr, get_dpr_details, list_issues, activity_health_snapshot, "
                + "traverse_entity) — see JPA-FIRST ROUTING in the system prompt. "
                + "ONLY warehouse tables under the bipros_analytics schema are accepted "
                + "(dim_*, fact_*, mv_*). The OLTP tables (project.*, activity.*, resource.*, "
                + "cost.*, evm.*, baseline.*, scheduling.*, risk.*, contract.*, permit.*, "
                + "udf.*, document.*) are NOT in the warehouse and will be rejected — for those "
                + "domains call the JPA tool above instead. Specifically NEVER pass "
                + "`project.dpr_issues` (use list_issues / activity_health_snapshot) or "
                + "`project.daily_progress_reports` (use query_dpr / get_dpr_details). "
                + "Read-only SQL; SELECT only; every query MUST include a `project_id` filter "
                + "(use IN for multi-project). Schema:\n\n"
                + schemaCatalog.full();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.put("sql", new ObjectMapper().createObjectNode().put("type", "string"));
        props.put("row_limit", new ObjectMapper().createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 5000));
        schema.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode();
        req.add("sql");
        schema.set("required", req);
        return schema;
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        String sql = input.path("sql").asText();
        int rowLimit = input.path("row_limit").asInt(500);
        // When the chat has a project in scope (user is on a project page or the
        // orchestrator has already resolved one), lock the SQL guard to that
        // single project. The LLM cannot then pick a different project_id —
        // SqlGuard will reject any UUID literal not in this list.
        // When no project is in scope, fall back to the user's accessible scope
        // (admin → all non-archived projects, non-admin → their scoped list).
        List<UUID> effectiveScope =
                ctx.projectId() != null ? List.of(ctx.projectId()) : ctx.scopedProjectIds();
        try {
            ClickHouseQueryService.QueryResult result =
                    queryService.runGuarded(sql, effectiveScope, rowLimit);
            return ToolResult.table("Query returned " + result.rowCount() + " rows" +
                    (result.truncated() ? " (truncated)" : ""), result.rows(), new String[]{"rows"});
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.warn("query_clickhouse failed: {} (root cause: {}: {})",
                    e.getMessage(), root.getClass().getSimpleName(), root.getMessage(), e);
            return ToolResult.error(root.getMessage() != null ? root.getMessage() : e.getMessage());
        }
    }
}
