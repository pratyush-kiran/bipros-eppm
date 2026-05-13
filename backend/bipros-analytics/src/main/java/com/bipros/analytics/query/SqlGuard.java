package com.bipros.analytics.query;

import com.bipros.common.exception.BusinessRuleException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guards AI-generated SQL. Enforces:
 * <ul>
 *   <li>SELECT only (no DML/DDL).</li>
 *   <li>Tables drawn from the analytics warehouse allow-list.</li>
 *   <li>{@code project_id} predicate present and every UUID literal compared to
 *       {@code project_id} is in {@code scopedProjectIds}. This stops the LLM
 *       from running {@code WHERE project_id = '&lt;any uuid it picked&gt;'} and
 *       reading projects the user can't access.</li>
 *   <li>{@code LIMIT} ≤ {@value #MAX_LIMIT}.</li>
 * </ul>
 *
 * <p>When the caller passes a single-element {@code scopedProjectIds}, the
 * effect is "this SQL may only touch that one project" — the natural
 * enforcement of {@code ctx.projectId()} when a project is in scope.
 */
@Component
public class SqlGuard {

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "dim_project", "dim_wbs", "dim_activity", "dim_resource", "dim_cost_account", "dim_calendar",
            "dim_risk", "dim_permit", "dim_permit_type", "dim_labour_designation",
            "fact_activity_progress_daily", "fact_resource_usage_daily", "fact_cost_daily",
            "fact_evm_daily", "fact_dpr_logs",
            "fact_dpr_manpower_daily", "fact_dpr_equipment_daily", "fact_dpr_material_daily",
            "fact_dpr_issues_daily",
            "fact_risk_snapshot_daily", "fact_permit_lifecycle", "fact_labour_daily",
            "mv_project_kpi_daily", "mv_portfolio_scurve_weekly", "mv_activity_weekly"
    );

    private static final int MAX_LIMIT = 5000;

    /**
     * Self-correcting hints for the AI orchestrator. When the model passes an OLTP table name
     * (or a warehouse-table name that's been retired), the error returned to the model points
     * at the right JPA tool so the next ReAct round can recover. Keys are bare table names
     * (with or without schema prefix) lower-cased.
     */
    private static final Map<String, String> TABLE_HINTS = Map.ofEntries(
            Map.entry("project.dpr_issues",
                    "OLTP table — for issue queries call list_issues (default group_by=activity) "
                            + "or activity_health_snapshot (per-activity rollup). JPA-backed, "
                            + "authoritative, immediately consistent. Warehouse equivalent for "
                            + "cross-project trends only: bipros_analytics.fact_dpr_issues_daily."),
            Map.entry("dpr_issues",
                    "warehouse-style name — the warehouse fact is bipros_analytics.fact_dpr_issues_daily, "
                            + "but for live single-project issue questions prefer list_issues or "
                            + "activity_health_snapshot (JPA, authoritative)."),
            Map.entry("project.daily_progress_reports",
                    "OLTP table — call query_dpr (rows + rollups) or get_dpr_details (single record "
                            + "drill-down). Warehouse equivalent: bipros_analytics.fact_dpr_logs."),
            Map.entry("daily_progress_reports",
                    "warehouse table name is bipros_analytics.fact_dpr_logs; for live questions "
                            + "prefer query_dpr / get_dpr_details (JPA)."),
            Map.entry("activity.activities",
                    "OLTP table — call list_activities, get_activity_full_context, or "
                            + "traverse_entity(entity_type=activity). Warehouse: bipros_analytics.dim_activity."),
            Map.entry("activities",
                    "warehouse dim name is bipros_analytics.dim_activity; for live questions "
                            + "prefer list_activities / get_activity_full_context."),
            Map.entry("project.projects",
                    "OLTP table — call list_projects. Warehouse: bipros_analytics.dim_project."),
            Map.entry("projects",
                    "warehouse dim name is bipros_analytics.dim_project; for live questions call list_projects."),
            Map.entry("project.wbs_nodes",
                    "OLTP table — call query_wbs or traverse_entity(entity_type=wbs_node). "
                            + "Warehouse: bipros_analytics.dim_wbs."),
            Map.entry("wbs_nodes",
                    "warehouse dim name is bipros_analytics.dim_wbs; for live questions call query_wbs."),
            Map.entry("resource.resources",
                    "OLTP table — call get_resource_profile, find_resource_deployment, or "
                            + "list_supervisors. Warehouse: bipros_analytics.dim_resource."),
            Map.entry("resources",
                    "warehouse dim name is bipros_analytics.dim_resource; for live questions prefer "
                            + "get_resource_profile / find_resource_deployment."),
            Map.entry("activity.activity_relationships",
                    "OLTP table — call query_relationships or traverse_entity(entity_type=activity)."),
            Map.entry("resource.resource_assignments",
                    "OLTP table — call list_activity_resources or summarize_activity_resources.")
    );

    private static final Set<String> OLTP_SCHEMA_PREFIXES = Set.of(
            "project.", "activity.", "resource.", "cost.", "evm.", "baseline.",
            "scheduling.", "risk.", "contract.", "permit.", "udf.", "document.",
            "gis.", "calendar.", "portfolio.", "admin.", "public.");

    public void validate(String sql, List<String> scopedProjectIds) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessRuleException("SQL_EMPTY", "SQL is empty");
        }

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new BusinessRuleException("SQL_PARSE_ERROR", "Failed to parse SQL: " + e.getMessage());
        }

        if (!(stmt instanceof Select)) {
            throw new BusinessRuleException("SQL_NOT_SELECT", "Only SELECT queries are allowed");
        }

        Select select = (Select) stmt;
        PlainSelect ps = (PlainSelect) select.getSelectBody();

        // Check tables
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tables = finder.getTableList(stmt);
        for (String t : tables) {
            String bare = t.replace("bipros_analytics.", "");
            if (!ALLOWED_TABLES.contains(bare)) {
                throw new BusinessRuleException("SQL_TABLE_NOT_ALLOWED",
                        buildTableNotAllowedMessage(t, bare));
            }
        }

        // Check project_id predicate presence (simple heuristic)
        String lower = sql.toLowerCase();
        if (!lower.contains("project_id")) {
            throw new BusinessRuleException("SQL_MISSING_PROJECT_FILTER",
                    "Query must include project_id filter");
        }

        // Validate every UUID literal compared to a `project_id` column is in scope.
        // This blocks the LLM from selecting a project the user cannot access — the
        // core defense against project-scope confusion in the agent loop.
        Set<String> scope = new HashSet<>(scopedProjectIds);
        ProjectIdScopeValidator validator = new ProjectIdScopeValidator(scope);
        if (ps.getWhere() != null) ps.getWhere().accept(validator);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getOnExpressions() != null) {
                    for (Expression on : j.getOnExpressions()) {
                        on.accept(validator);
                    }
                }
            }
        }

        // Check limit
        if (ps.getLimit() != null && ps.getLimit().getRowCount() != null) {
            String limitStr = ps.getLimit().getRowCount().toString();
            try {
                int limit = Integer.parseInt(limitStr);
                if (limit > MAX_LIMIT) {
                    throw new BusinessRuleException("SQL_LIMIT_TOO_HIGH",
                            "LIMIT exceeds " + MAX_LIMIT);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * Build the error message returned to the AI orchestrator when a table is rejected.
     * Carries a JPA-tool hint so the next ReAct round can recover without another guess.
     */
    private static String buildTableNotAllowedMessage(String original, String bare) {
        String lower = original == null ? "" : original.toLowerCase();
        String bareLower = bare == null ? "" : bare.toLowerCase();
        String hint = TABLE_HINTS.get(lower);
        if (hint == null) hint = TABLE_HINTS.get(bareLower);
        if (hint == null) {
            for (String prefix : OLTP_SCHEMA_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    hint = "OLTP schema (" + prefix.substring(0, prefix.length() - 1)
                            + ") is not in the warehouse. Use the JPA tool for this domain: "
                            + "list_activities / query_dpr / list_issues / activity_health_snapshot "
                            + "/ traverse_entity / get_resource_profile / cost_breakdown / "
                            + "analyze_schedule / analyze_risk — pick the one that matches the question.";
                    break;
                }
            }
        }
        if (hint == null) {
            return "Table not allowed: " + bare + ". Allowed warehouse tables start with "
                    + "bipros_analytics.* (dim_*, fact_*, mv_*). Call describe_schema for the catalog, "
                    + "or switch to a JPA tool (list_*, query_dpr, list_issues, "
                    + "activity_health_snapshot, traverse_entity) for live single-project answers.";
        }
        return "Table not allowed: " + bare + " — " + hint;
    }

    /**
     * Walks the WHERE / JOIN-ON expression tree and validates every literal
     * compared to a {@code project_id} column is in the authorized scope.
     * Comparisons of column-to-column (e.g. {@code f.project_id = a.project_id}
     * in a JOIN) are ignored — only string literals trigger validation.
     */
    private static final class ProjectIdScopeValidator extends ExpressionVisitorAdapter<Void> {
        private final Set<String> scope;

        ProjectIdScopeValidator(Set<String> scope) {
            this.scope = scope;
        }

        @Override
        public <S> Void visit(EqualsTo expr, S context) {
            checkEquality(expr.getLeftExpression(), expr.getRightExpression());
            checkEquality(expr.getRightExpression(), expr.getLeftExpression());
            return super.visit(expr, context);
        }

        @Override
        public <S> Void visit(InExpression expr, S context) {
            Expression left = expr.getLeftExpression();
            if (!isProjectIdColumn(left)) {
                return super.visit(expr, context);
            }
            Expression right = expr.getRightExpression();
            if (right instanceof ExpressionList<?>) {
                for (Expression e : ((ExpressionList<?>) right).getExpressions()) {
                    if (e instanceof StringValue) {
                        validate(((StringValue) e).getValue());
                    }
                }
            }
            return super.visit(expr, context);
        }

        private void checkEquality(Expression columnSide, Expression valueSide) {
            if (!isProjectIdColumn(columnSide)) return;
            if (!(valueSide instanceof StringValue)) return;
            validate(((StringValue) valueSide).getValue());
        }

        private boolean isProjectIdColumn(Expression e) {
            if (!(e instanceof Column)) return false;
            String name = ((Column) e).getColumnName();
            return name != null && name.equalsIgnoreCase("project_id");
        }

        private void validate(String uuid) {
            if (uuid == null || uuid.isBlank()) return;
            if (!scope.contains(uuid)) {
                throw new BusinessRuleException(
                        "SQL_PROJECT_OUT_OF_SCOPE",
                        "Query references project_id '" + uuid + "' which is not in your "
                                + "authorized scope. Use a project from your accessible list. "
                                + "Authorized scope (truncated): "
                                + previewScope(scope));
            }
        }

        private static String previewScope(Set<String> scope) {
            if (scope.isEmpty()) return "(none)";
            if (scope.size() <= 3) return String.join(", ", scope);
            return scope.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("")
                    + ", … (" + (scope.size() - 3) + " more)";
        }
    }
}
