package com.bipros.ai.tool.team;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Project team roster — the full PM / CM / Engineer / Supervisor reporting chain
 * stored in {@code project.project_team}. Each row is enriched with the User identity
 * panel (employee_code, username, name, email) so the AI can answer "who is the PM"
 * without a follow-up resolve_entity call.
 *
 * <p>Distinct from {@code list_project_supervisors} (supervisors only, joined from
 * activities + DPRs) — this tool reads the explicit team table that drives the DBS
 * Supervisor → Engineer → CM → PM rollup chain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetProjectTeamTool extends ProjectScopedTool {

    @PersistenceContext private EntityManager em;

    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_project_team";
    }

    @Override
    public String description() {
        return "Project team / reporting chain — returns every member of project_team with role "
                + "(PROJECT_MANAGER, CONSTRUCTION_MANAGER, ENGINEER, SUPERVISOR, SITE_MANAGER, "
                + "etc.), the User identity (employee_code, username, full name, email), and the "
                + "`reports_to_user_id` link that drives the DBS rollup. Use for 'who is the PM "
                + "of project X', 'list the engineers on this project', 'which CM does supervisor "
                + "Y report to', 'show the reporting chain'. "
                + "Optional `role` filter (case-insensitive, exact match on the role column). "
                + "By default only currently active members are returned (active_from <= today "
                + "and active_to is null or >= today); pass `includeInactive=true` to see "
                + "historical rows too. "
                + "RESPONSE FORMAT: echo team members the same way the UI dropdown does — "
                + "'EMP-001 — Subrat Mohapatra (Engineer)' — when the user names someone in prose, "
                + "use this tool first to resolve them to a User UUID before calling other tools "
                + "like dbs_financial(level=ENGINEER/CM).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("projectId", mapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope."));
        props.set("role", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Filter by role: PROJECT_MANAGER, CONSTRUCTION_MANAGER, ENGINEER, SUPERVISOR, SITE_MANAGER. Case-insensitive."));
        props.set("includeInactive", mapper.createObjectNode()
                .put("type", "boolean")
                .put("description", "When true, returns historical / future members too. Default false (only currently active).")
                .put("default", false));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode());
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("get_project_team needs a projectId (or a project in scope).");
        }

        String role = text(input, "role");
        String roleUpper = role == null ? null : role.toUpperCase(Locale.ROOT);
        boolean includeInactive = input.path("includeInactive").asBoolean(false);

        StringBuilder sql = new StringBuilder()
                .append("SELECT t.id, t.user_id, t.role, t.reports_to_user_id, ")
                .append("       t.active_from, t.active_to, ")
                .append("       COALESCE(NULLIF(u.employee_code, ''), u.username, '') AS code, ")
                .append("       COALESCE(u.employee_code, '') AS employee_code, ")
                .append("       COALESCE(u.username, '')      AS username, ")
                .append("       COALESCE(u.email, '')         AS email, ")
                .append("       COALESCE(u.first_name, '')    AS first_name, ")
                .append("       COALESCE(u.last_name, '')     AS last_name, ")
                .append("       COALESCE(NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), u.username, '') AS display_name, ")
                .append("       COALESCE(NULLIF(TRIM(CONCAT_WS(' ', boss.first_name, boss.last_name)), ''), boss.username, '') AS reports_to_name, ")
                .append("       COALESCE(NULLIF(boss.employee_code, ''), boss.username, '') AS reports_to_code ")
                .append("  FROM project.project_team t ")
                .append("  LEFT JOIN public.users u    ON u.id = t.user_id ")
                .append("  LEFT JOIN public.users boss ON boss.id = t.reports_to_user_id ")
                .append(" WHERE t.project_id = :projectId ");
        if (roleUpper != null) {
            sql.append(" AND UPPER(t.role) = :role ");
        }
        if (!includeInactive) {
            sql.append(" AND (t.active_from IS NULL OR t.active_from <= :today) ")
               .append(" AND (t.active_to   IS NULL OR t.active_to   >= :today) ");
        }
        sql.append(" ORDER BY CASE UPPER(t.role) ")
           .append("            WHEN 'PROJECT_MANAGER'      THEN 1 ")
           .append("            WHEN 'CONSTRUCTION_MANAGER' THEN 2 ")
           .append("            WHEN 'SITE_MANAGER'         THEN 3 ")
           .append("            WHEN 'ENGINEER'             THEN 4 ")
           .append("            WHEN 'SUPERVISOR'           THEN 5 ")
           .append("            ELSE 9 END, display_name");

        Query q = em.createNativeQuery(sql.toString());
        q.setParameter("projectId", projectId);
        if (roleUpper != null) q.setParameter("role", roleUpper);
        if (!includeInactive) q.setParameter("today", LocalDate.now());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("project_id", projectId.toString());
        if (role != null) wrapper.put("role_filter", roleUpper);
        wrapper.put("include_inactive", includeInactive);

        ArrayNode arr = mapper.createArrayNode();
        int pmCount = 0, cmCount = 0, engCount = 0, supCount = 0;
        for (Object[] r : rows) {
            UUID memberId = (UUID) r[0];
            UUID userId = (UUID) r[1];
            String memberRole = r[2] != null ? r[2].toString() : null;
            UUID reportsTo = (UUID) r[3];
            LocalDate activeFrom = r[4] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[4];
            LocalDate activeTo = r[5] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[5];

            ObjectNode row = mapper.createObjectNode();
            row.put("id", memberId != null ? memberId.toString() : null);
            row.put("user_id", userId != null ? userId.toString() : null);
            row.put("role", memberRole);
            row.put("user_code", emptyToNull(str(r[6])));
            row.put("employee_code", emptyToNull(str(r[7])));
            row.put("username", emptyToNull(str(r[8])));
            row.put("email", emptyToNull(str(r[9])));
            row.put("first_name", emptyToNull(str(r[10])));
            row.put("last_name", emptyToNull(str(r[11])));
            row.put("display_name", emptyToNull(str(r[12])));
            row.put("reports_to_user_id", reportsTo != null ? reportsTo.toString() : null);
            row.put("reports_to_name", emptyToNull(str(r[13])));
            row.put("reports_to_code", emptyToNull(str(r[14])));
            row.put("active_from", activeFrom != null ? activeFrom.toString() : null);
            row.put("active_to", activeTo != null ? activeTo.toString() : null);
            arr.add(row);

            if (memberRole != null) {
                switch (memberRole.toUpperCase(Locale.ROOT)) {
                    case "PROJECT_MANAGER" -> pmCount++;
                    case "CONSTRUCTION_MANAGER" -> cmCount++;
                    case "ENGINEER" -> engCount++;
                    case "SUPERVISOR" -> supCount++;
                    default -> {}
                }
            }
        }

        wrapper.set("members", arr);
        wrapper.put("count", arr.size());
        ObjectNode breakdown = mapper.createObjectNode();
        breakdown.put("project_manager", pmCount);
        breakdown.put("construction_manager", cmCount);
        breakdown.put("engineer", engCount);
        breakdown.put("supervisor", supCount);
        wrapper.set("role_breakdown", breakdown);

        String summary;
        if (arr.size() == 0) {
            summary = roleUpper != null
                    ? "No " + roleUpper + " team members on this project."
                    : "No team members on this project.";
        } else {
            summary = String.format(Locale.ROOT,
                    "%d team member%s (PM=%d, CM=%d, Engineer=%d, Supervisor=%d).",
                    arr.size(), arr.size() == 1 ? "" : "s",
                    pmCount, cmCount, engCount, supCount);
        }
        return ToolResult.ok(summary, wrapper);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
