package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Returns what a User-supervisor is doing on the current project: activities they're the
 * currently-assigned supervisor of, DPRs they actually submitted, and total cost they
 * supervised. Two senses of "supervisor" — disambiguated in the output:
 *
 * <ul>
 *   <li>{@code activity.supervisor_user_id} — currently assigned supervisor.
 *   <li>{@code daily_progress_reports.supervisor_user_id} — the User who supervised on that day.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSupervisorWorkloadTool implements Tool {

    @PersistenceContext private EntityManager em;

    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_supervisor_workload";
    }

    @Override
    public String description() {
        return "Show what a User (supervisor) is currently working on. Inputs: user_id (UUID, "
                + "required — a User UUID, NOT a Resource UUID). Optional from_date / to_date "
                + "filter the DPRs and cost rollup. Returns: activities where supervisor_user_id "
                + "= the user (currently assigned), DPRs they actually submitted in the date "
                + "window, total DPR cost they supervised. "
                + "WHEN THE USER NAMES A SUPERVISOR IN PROSE: call list_project_supervisors first "
                + "(optionally with name_filter=<name>) to translate the name to a User UUID. "
                + "Do NOT use resolve_entity(kind='supervisor') — that returns a Resource UUID "
                + "from the legacy model and will not match the User-FK columns. "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        addStringProp(props, "user_id", "UUID of the user (supervisor).");
        addStringProp(props, "from_date", "ISO date — lower bound for the DPR window.");
        addStringProp(props, "to_date", "ISO date — upper bound for the DPR window.");
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("user_id");
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("get_supervisor_workload requires a project in scope.");
        }
        UUID userId = parseUuid(text(input, "user_id"));
        if (userId == null) {
            return ToolResult.error("user_id is required.");
        }
        LocalDate fromDate = parseDate(input, "from_date");
        LocalDate toDate = parseDate(input, "to_date");

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("user_id", userId.toString());
        wrapper.put("project_id", projectId.toString());
        if (fromDate != null) wrapper.put("from_date", fromDate.toString());
        if (toDate != null) wrapper.put("to_date", toDate.toString());

        // ── assigned activities ─────────────────────────────────────────
        // Multi-supervisor model: join through activity.activity_supervisors so that
        // co-supervised activities are returned for ANY supervisor in their set, not just
        // the legacy single-column cache.
        Query qAssigned = em.createNativeQuery(
                "SELECT a.id, a.code, a.name, a.status, a.percent_complete "
                        + "FROM activity.activities a "
                        + "WHERE a.project_id = :projectId "
                        + "  AND EXISTS ( "
                        + "    SELECT 1 FROM activity.activity_supervisors s "
                        + "     WHERE s.activity_id = a.id "
                        + "       AND s.user_id = :userId) "
                        + "ORDER BY a.code");
        qAssigned.setParameter("projectId", projectId);
        qAssigned.setParameter("userId", userId);
        @SuppressWarnings("unchecked")
        List<Object[]> assignedRows = qAssigned.getResultList();
        ArrayNode assigned = mapper.createArrayNode();
        for (Object[] r : assignedRows) {
            ObjectNode row = mapper.createObjectNode();
            row.put("activity_id", r[0] != null ? r[0].toString() : null);
            row.put("code", r[1] != null ? r[1].toString() : null);
            row.put("name", r[2] != null ? r[2].toString() : null);
            row.put("status", r[3] != null ? r[3].toString() : null);
            row.put("percent_complete", r[4] != null ? r[4].toString() : null);
            assigned.add(row);
        }
        wrapper.set("assigned_activities", assigned);
        wrapper.put("assigned_activity_count", assigned.size());

        // ── DPRs submitted by this user ─────────────────────────────────
        StringBuilder dprSql = new StringBuilder(
                "SELECT d.id, d.report_date, d.activity_id, a.code, a.name, "
                        + "       COALESCE(d.approval_status, 'DRAFT'), d.qty_executed "
                        + "  FROM project.daily_progress_reports d "
                        + "  LEFT JOIN activity.activities a ON a.id = d.activity_id "
                        + " WHERE d.project_id = :projectId "
                        + "   AND d.supervisor_user_id = :userId");
        if (fromDate != null) dprSql.append(" AND d.report_date >= :fromDate");
        if (toDate != null) dprSql.append(" AND d.report_date <= :toDate");
        dprSql.append(" ORDER BY d.report_date DESC LIMIT 200");
        Query qDprs = em.createNativeQuery(dprSql.toString());
        qDprs.setParameter("projectId", projectId);
        qDprs.setParameter("userId", userId);
        if (fromDate != null) qDprs.setParameter("fromDate", fromDate);
        if (toDate != null) qDprs.setParameter("toDate", toDate);
        @SuppressWarnings("unchecked")
        List<Object[]> dprRows = qDprs.getResultList();
        ArrayNode dprs = mapper.createArrayNode();
        for (Object[] r : dprRows) {
            ObjectNode row = mapper.createObjectNode();
            row.put("dpr_id", r[0] != null ? r[0].toString() : null);
            row.put("report_date", r[1] != null ? r[1].toString() : null);
            row.put("activity_id", r[2] != null ? r[2].toString() : null);
            row.put("activity_code", r[3] != null ? r[3].toString() : null);
            row.put("activity_name", r[4] != null ? r[4].toString() : null);
            row.put("approval_status", r[5] != null ? r[5].toString() : null);
            row.put("qty_executed", r[6] != null ? r[6].toString() : null);
            dprs.add(row);
        }
        wrapper.set("dprs", dprs);
        wrapper.put("dpr_count", dprs.size());

        // ── cost supervised in the window ───────────────────────────────
        BigDecimal cost = sumCost(projectId, userId, fromDate, toDate);
        wrapper.put("cost_supervised", cost != null ? cost.toPlainString() : "0");

        String summary = "Supervisor has " + assigned.size() + " assigned activit"
                + (assigned.size() == 1 ? "y" : "ies") + " and submitted "
                + dprs.size() + " DPR" + (dprs.size() == 1 ? "" : "s")
                + " (cost ₹" + (cost != null ? cost.toPlainString() : "0") + ").";
        return ToolResult.ok(summary, wrapper);
    }

    private BigDecimal sumCost(UUID projectId, UUID userId, LocalDate fromDate, LocalDate toDate) {
        StringBuilder sb = new StringBuilder(
                "SELECT COALESCE(SUM(c.line_cost), 0) FROM ("
                        + "   SELECT line_cost FROM project.dpr_manpower c "
                        + "     JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
                        + "    WHERE d.project_id = :projectId AND d.supervisor_user_id = :userId "
                        + "      AND COALESCE(d.approval_status,'DRAFT') IN ('SUBMITTED','APPROVED')");
        appendDateBounds(sb, fromDate, toDate);
        sb.append(" UNION ALL "
                + "   SELECT line_cost FROM project.dpr_equipment c "
                + "     JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
                + "    WHERE d.project_id = :projectId AND d.supervisor_user_id = :userId "
                + "      AND COALESCE(d.approval_status,'DRAFT') IN ('SUBMITTED','APPROVED')");
        appendDateBounds(sb, fromDate, toDate);
        sb.append(" UNION ALL "
                + "   SELECT line_cost FROM project.dpr_material c "
                + "     JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
                + "    WHERE d.project_id = :projectId AND d.supervisor_user_id = :userId "
                + "      AND COALESCE(d.approval_status,'DRAFT') IN ('SUBMITTED','APPROVED')");
        appendDateBounds(sb, fromDate, toDate);
        sb.append(") c");
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("projectId", projectId);
        q.setParameter("userId", userId);
        if (fromDate != null) q.setParameter("fromDate", fromDate);
        if (toDate != null) q.setParameter("toDate", toDate);
        Object res = q.getSingleResult();
        if (res == null) return BigDecimal.ZERO;
        if (res instanceof BigDecimal b) return b;
        return new BigDecimal(res.toString());
    }

    private void appendDateBounds(StringBuilder sb, LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null) sb.append(" AND d.report_date >= :fromDate");
        if (toDate != null) sb.append(" AND d.report_date <= :toDate");
    }

    private void addStringProp(ObjectNode props, String name, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        props.set(name, n);
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parseDate(JsonNode in, String field) {
        String s = text(in, field);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
