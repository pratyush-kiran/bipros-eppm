package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.analytics.query.ClickHouseQueryService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryClickHouseTool extends ProjectScopedTool {

    private final ClickHouseQueryService queryService;
    private final SchemaCatalog schemaCatalog;
    private final ProjectRepository projectRepository;
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
                + "traverse_entity, get_activity_cost, get_capacity_utilization, "
                + "query_role_rates, query_productivity_norm, get_supervisor_workload) — "
                + "see JPA-FIRST ROUTING in the system prompt. "
                + "ONLY warehouse tables under the bipros_analytics schema are accepted "
                + "(dim_*, fact_*, mv_*). The OLTP tables (project.*, activity.*, resource.*, "
                + "cost.*, evm.*, baseline.*, scheduling.*, risk.*, contract.*, permit.*, "
                + "udf.*, document.*) are NOT in the warehouse and will be rejected — for those "
                + "domains call the JPA tool above instead. Specifically NEVER pass "
                + "`project.dpr_issues` (use list_issues / activity_health_snapshot) or "
                + "`project.daily_progress_reports` (use query_dpr / get_dpr_details). "
                + "\n\n"
                + "LEGACY GUARDS (do NOT use — these are frozen as of 2026-05-13):\n"
                + "  • dim_resource.unit_rate / role_code / role_name — frozen rate-master "
                + "snapshot. Use query_role_rates for any rate question — it walks the "
                + "project-override → variant chain in OLTP.\n"
                + "  • dim_activity.responsible_resource_id / responsible_resource_name — "
                + "legacy Resource-based supervisor, now null on new rows. The User-based "
                + "supervisor is in dim_activity.supervisor_user_id (and dim_user).\n"
                + "  • fact_resource_usage_daily.cost — computed at ETL with the legacy "
                + "rate-master snapshot; not override-aware. Use get_activity_cost for "
                + "activity-level cost, or list_activity_resources / find_resource_deployment "
                + "for per-resource rate-precise cost.\n"
                + "  • fact_dpr_logs.supervisor_user_id — FK target diverged across the "
                + "2026-05-13 cutover (legacy rows hold Resource.id; new rows hold User.id). "
                + "Until the Phase-4 backfill completes, prefer query_dpr (reads OLTP "
                + "daily_progress_reports.supervisor_user_id directly).\n"
                + "  • Any rate_master_* / project_resources / resources.role table — these "
                + "have been replaced by the role-owned rate book. Do not reference.\n\n"
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
        // When no project is in scope:
        //  - non-admin: their scopedProjectIds list (already row-filtered upstream)
        //  - admin: ctx.scopedProjectIds() is empty by convention (admins are not
        //    row-filtered); expand to every non-archived project so SqlGuard
        //    admits any project_id the LLM picked from list_projects /
        //    resolve_entity. Without this expansion every admin portfolio-mode
        //    warehouse query fails with SQL_PROJECT_OUT_OF_SCOPE.
        List<UUID> effectiveScope;
        if (ctx.projectId() != null) {
            effectiveScope = List.of(ctx.projectId());
        } else if ("ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || ctx.scopedProjectIds().isEmpty())) {
            List<UUID> all = new ArrayList<>();
            for (Project p : projectRepository.findAllByArchivedAtIsNull()) all.add(p.getId());
            effectiveScope = all;
        } else {
            effectiveScope = ctx.scopedProjectIds();
        }
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
