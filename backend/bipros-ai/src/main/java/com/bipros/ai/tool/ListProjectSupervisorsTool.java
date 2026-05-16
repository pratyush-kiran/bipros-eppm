package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
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
 * Canonical "supervisors on this project" lookup for the role-rate model. UNIONs both surfaces
 * where a supervisor identity surfaces in the new model:
 *
 * <ul>
 *   <li>{@code activity.activities.supervisor_user_id} — the assigned supervisor for an activity
 *       (visible on the activity sidebar). Present even when no DPR has been filed yet.</li>
 *   <li>{@code project.daily_progress_reports.supervisor_user_id} — who actually filed a DPR.
 *       Optional date window narrows this side only; activity assignments are always returned.</li>
 * </ul>
 *
 * <p>Each returned row carries both {@code activity_count} and {@code dpr_count}, plus a
 * {@code sources} array tagged with {@code ACTIVITY} and/or {@code DPR}. This is the only AI
 * tool that returns User UUIDs matching {@code supervisor_user_id} in the new model —
 * {@code list_supervisors} / {@code resolve_entity(kind='supervisor')} both speak the legacy
 * Resource/ManpowerMaster schema.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListProjectSupervisorsTool implements Tool {

    @PersistenceContext private EntityManager em;

    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "list_project_supervisors";
    }

    @Override
    public String description() {
        return "Distinct User-FK supervisors on the current project — UNIONed across BOTH the "
                + "activity-assigned surface (activity.activities.supervisor_user_id, what the "
                + "activity sidebar shows) AND the DPR-submitted surface "
                + "(daily_progress_reports.supervisor_user_id, who actually filed reports). "
                + "Returns one row per User UUID with the FULL identity panel from public.users: "
                + "supervisor_user_id, employee_code (e.g. 'EMP-001' — the value the UI dropdown "
                + "shows as the supervisor identifier), username (e.g. 'subrat'), email, "
                + "first_name, last_name, supervisor_name (display name, prefers full name then "
                + "DPR snapshot then username), supervisor_code (defaults to employee_code, falls "
                + "back to username — pick this when echoing the supervisor back to the user), "
                + "activity_count, dpr_count, and sources=['ACTIVITY' | 'DPR' | 'DPR_BY_NAME']. "
                + "DPR_BY_NAME rows surface DPRs whose supervisor_user_id was not "
                + "resolved at write time — the snapshot name exists but the User join "
                + "is missing; treat these as a data-quality flag and report them "
                + "explicitly when they show up. "
                + "ALWAYS call this for any 'who are the supervisors on project X', 'list "
                + "supervisors', 'distinct supervisors across activities', or 'supervisors "
                + "available' question — it is the canonical roster for the role-rate model. "
                + "Also call it first when the user names a supervisor in prose and you need a "
                + "User UUID — pass name_filter and it will substring-match (case-insensitive) "
                + "against ALL FIVE identity fields: employee_code, username, email, first_name, "
                + "last_name. So 'EMP-001', 'subrat', 'subrat@bipros.com', and 'Subrat mohapatra' "
                + "all resolve to the same user. "
                + "Do NOT use list_supervisors (legacy responsibleResourceId, returns 0) or "
                + "resolve_entity(kind='supervisor') (legacy Resource UUIDs that won't match) "
                + "for these questions. Optional from_date / to_date narrow the DPR side only — "
                + "an activity supervisor with zero DPRs in the window still appears with "
                + "dpr_count=0 and sources=['ACTIVITY']. "
                + "RESPONSE FORMAT: when answering the user, echo the supervisor in the same "
                + "form they used. If they said 'EMP-001', answer with 'EMP-001 — Subrat "
                + "mohapatra' to match the UI dropdown. If they said 'subrat', use the username "
                + "form. This keeps your answer consistent with what they see on screen.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        addStringProp(props, "from_date",
                "ISO date (yyyy-MM-dd) — narrows the DPR-side count only. Activity-assigned "
                        + "supervisors are always returned regardless of date.");
        addStringProp(props, "to_date",
                "ISO date (yyyy-MM-dd) — narrows the DPR-side count only.");
        addStringProp(props, "name_filter",
                "Optional case-insensitive substring filter applied across all five identity "
                        + "fields: employee_code (e.g. 'EMP-001'), username (e.g. 'subrat'), "
                        + "email, first_name, last_name. Pass the literal text the user typed; "
                        + "no need to know which field it refers to.");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode());
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("list_project_supervisors requires a project in scope.");
        }
        LocalDate fromDate = parseDate(input, "from_date");
        LocalDate toDate = parseDate(input, "to_date");
        String nameFilter = text(input, "name_filter");
        String normalisedFilter = nameFilter == null
                ? null : nameFilter.toLowerCase(Locale.ROOT);

        Query q = em.createNativeQuery(
                "WITH activity_sups AS ("
                        // Multi-supervisor: counts via the join table so a user is
                        // listed even when they're a co-supervisor (not the legacy
                        // first-in-list cache on activities.supervisor_user_id).
                        + "  SELECT s.user_id AS user_id, "
                        + "         COUNT(DISTINCT s.activity_id) AS activity_count "
                        + "    FROM activity.activity_supervisors s "
                        + "    JOIN activity.activities a ON a.id = s.activity_id "
                        + "   WHERE a.project_id = :projectId "
                        + "   GROUP BY s.user_id "
                        + "), "
                        + "dpr_sups AS ("
                        + "  SELECT d.supervisor_user_id AS user_id, "
                        + "         MAX(d.supervisor_name) AS supervisor_name_snapshot, "
                        + "         COUNT(*)            AS dpr_count "
                        + "    FROM project.daily_progress_reports d "
                        + "   WHERE d.project_id = :projectId "
                        + "     AND d.supervisor_user_id IS NOT NULL "
                        + "     AND (CAST(:fromDate AS date) IS NULL OR d.report_date >= CAST(:fromDate AS date)) "
                        + "     AND (CAST(:toDate AS date) IS NULL OR d.report_date <= CAST(:toDate AS date)) "
                        + "   GROUP BY d.supervisor_user_id "
                        + ") "
                        + "SELECT COALESCE(a.user_id, d.user_id) AS user_id, "
                        + "       COALESCE(NULLIF(u.employee_code, ''), u.username, '') AS supervisor_code, "
                        + "       COALESCE(u.username, '')          AS username, "
                        + "       COALESCE(u.employee_code, '')     AS employee_code, "
                        + "       COALESCE(u.email, '')             AS email, "
                        + "       COALESCE(u.first_name, '')        AS first_name, "
                        + "       COALESCE(u.last_name, '')         AS last_name, "
                        + "       COALESCE(NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), "
                        + "                d.supervisor_name_snapshot, "
                        + "                u.username, "
                        + "                '') AS supervisor_name, "
                        + "       COALESCE(a.activity_count, 0) AS activity_count, "
                        + "       COALESCE(d.dpr_count, 0)       AS dpr_count, "
                        + "       (a.user_id IS NOT NULL)        AS from_activity, "
                        + "       (d.user_id IS NOT NULL)        AS from_dpr "
                        + "  FROM activity_sups a "
                        + "  FULL OUTER JOIN dpr_sups d ON d.user_id = a.user_id "
                        + "  LEFT JOIN public.users u "
                        + "    ON u.id = COALESCE(a.user_id, d.user_id) "
                        + " ORDER BY activity_count DESC, dpr_count DESC, supervisor_name");

        // Belt-and-braces second query: DPRs whose supervisor_user_id is NULL but
        // whose snapshot supervisor_name is non-empty. The seeders write a non-null
        // user id for OMAN-Demo, but workbook imports / partial backfills can land
        // DPRs with only a name, and dropping them silently looks identical to
        // "this person filed no DPRs" — exactly the bug the gate is trying to
        // prevent. We surface them as a separate row with a data_quality_warning.
        Query qOrphan = em.createNativeQuery(
                "SELECT d.supervisor_name AS supervisor_name, "
                        + "       COUNT(*) AS dpr_count "
                        + "  FROM project.daily_progress_reports d "
                        + " WHERE d.project_id = :projectId "
                        + "   AND d.supervisor_user_id IS NULL "
                        + "   AND d.supervisor_name IS NOT NULL "
                        + "   AND TRIM(d.supervisor_name) <> '' "
                        + "   AND (CAST(:fromDate AS date) IS NULL OR d.report_date >= CAST(:fromDate AS date)) "
                        + "   AND (CAST(:toDate AS date) IS NULL OR d.report_date <= CAST(:toDate AS date)) "
                        + " GROUP BY d.supervisor_name "
                        + " ORDER BY dpr_count DESC");
        qOrphan.setParameter("projectId", projectId);
        qOrphan.setParameter("fromDate", fromDate);
        qOrphan.setParameter("toDate", toDate);
        q.setParameter("projectId", projectId);
        q.setParameter("fromDate", fromDate);
        q.setParameter("toDate", toDate);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        @SuppressWarnings("unchecked")
        List<Object[]> orphanRows = qOrphan.getResultList();

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("project_id", projectId.toString());
        if (fromDate != null) wrapper.put("from_date", fromDate.toString());
        if (toDate != null) wrapper.put("to_date", toDate.toString());
        if (nameFilter != null) wrapper.put("name_filter", nameFilter);

        ArrayNode arr = mapper.createArrayNode();
        int activityOnly = 0;
        int dprOnly = 0;
        int both = 0;
        for (Object[] r : rows) {
            UUID userId = (UUID) r[0];
            String code = r[1] != null ? r[1].toString() : null;
            String username = r[2] != null ? r[2].toString() : null;
            String employeeCode = r[3] != null ? r[3].toString() : null;
            String email = r[4] != null ? r[4].toString() : null;
            String firstName = r[5] != null ? r[5].toString() : null;
            String lastName = r[6] != null ? r[6].toString() : null;
            String name = r[7] != null ? r[7].toString() : null;
            long activityCount = ((Number) r[8]).longValue();
            long dprCount = ((Number) r[9]).longValue();
            boolean fromActivity = toBool(r[10]);
            boolean fromDpr = toBool(r[11]);

            if (normalisedFilter != null) {
                // Match against the full identity panel: employee_code, username, email,
                // first_name, last_name, plus the display name (which is the DPR snapshot
                // for users without first/last on file). This way "EMP-001", "subrat",
                // "subrat@bipros.com", and "Subrat mohapatra" all resolve to the same user.
                String[] haystack = {
                        employeeCode == null ? "" : employeeCode.toLowerCase(Locale.ROOT),
                        username == null ? "" : username.toLowerCase(Locale.ROOT),
                        email == null ? "" : email.toLowerCase(Locale.ROOT),
                        firstName == null ? "" : firstName.toLowerCase(Locale.ROOT),
                        lastName == null ? "" : lastName.toLowerCase(Locale.ROOT),
                        name == null ? "" : name.toLowerCase(Locale.ROOT)
                };
                boolean matched = false;
                for (String h : haystack) {
                    if (h.contains(normalisedFilter)) { matched = true; break; }
                }
                if (!matched) continue;
            }

            ObjectNode row = mapper.createObjectNode();
            row.put("supervisor_user_id", userId != null ? userId.toString() : null);
            row.put("supervisor_code", code);
            row.put("employee_code", emptyToNull(employeeCode));
            row.put("username", emptyToNull(username));
            row.put("email", emptyToNull(email));
            row.put("first_name", emptyToNull(firstName));
            row.put("last_name", emptyToNull(lastName));
            row.put("supervisor_name", name);
            row.put("activity_count", activityCount);
            row.put("dpr_count", dprCount);
            ArrayNode sources = mapper.createArrayNode();
            if (fromActivity) sources.add("ACTIVITY");
            if (fromDpr) sources.add("DPR");
            row.set("sources", sources);
            arr.add(row);

            if (fromActivity && fromDpr) both++;
            else if (fromActivity) activityOnly++;
            else if (fromDpr) dprOnly++;
        }

        // Append orphan DPR rows: supervisor_user_id IS NULL but a snapshot
        // name exists. These are NOT joinable to public.users, so we cannot
        // confirm the identity — surface them with a data_quality_warning so
        // the model knows to flag the gap rather than silently treat the count
        // as zero. Subject to the same name_filter as the joined rows above.
        int orphanByName = 0;
        for (Object[] r : orphanRows) {
            String snapshotName = r[0] != null ? r[0].toString() : null;
            long dprCount = r[1] == null ? 0 : ((Number) r[1]).longValue();
            if (snapshotName == null || snapshotName.isBlank()) continue;
            if (normalisedFilter != null
                    && !snapshotName.toLowerCase(Locale.ROOT).contains(normalisedFilter)) {
                continue;
            }
            ObjectNode row = mapper.createObjectNode();
            row.putNull("supervisor_user_id");
            row.putNull("supervisor_code");
            row.putNull("employee_code");
            row.putNull("username");
            row.putNull("email");
            row.putNull("first_name");
            row.putNull("last_name");
            row.put("supervisor_name", snapshotName);
            row.put("activity_count", 0);
            row.put("dpr_count", dprCount);
            ArrayNode sources = mapper.createArrayNode();
            sources.add("DPR_BY_NAME");
            row.set("sources", sources);
            row.put("data_quality_warning",
                    "supervisor_user_id is NULL on these DPRs — identity not joined to a User row. "
                            + "May or may not be the same person as a similarly-named user above.");
            arr.add(row);
            orphanByName++;
        }

        wrapper.set("supervisors", arr);
        wrapper.put("count", arr.size());
        ObjectNode breakdown = mapper.createObjectNode();
        breakdown.put("both_activity_and_dpr", both);
        breakdown.put("activity_only", activityOnly);
        breakdown.put("dpr_only", dprOnly);
        breakdown.put("dpr_by_name_only", orphanByName);
        wrapper.set("source_breakdown", breakdown);

        String summary;
        if (arr.size() == 0) {
            summary = nameFilter != null
                    ? "No supervisors match \"" + nameFilter + "\" on this project."
                    : "No supervisors found on this project (no activity assignments and no DPRs).";
        } else if (arr.size() == 1) {
            ObjectNode only = (ObjectNode) arr.get(0);
            summary = "1 supervisor: " + only.path("supervisor_name").asText()
                    + " (" + only.path("supervisor_code").asText() + ", "
                    + only.path("activity_count").asLong() + " activit"
                    + (only.path("activity_count").asLong() == 1 ? "y" : "ies") + ", "
                    + only.path("dpr_count").asLong() + " DPR"
                    + (only.path("dpr_count").asLong() == 1 ? "" : "s") + ").";
        } else {
            summary = arr.size() + " distinct supervisors on project (" + both
                    + " with both activity assignments and DPRs, " + activityOnly
                    + " activity-only, " + dprOnly + " DPR-only).";
        }
        return ToolResult.ok(summary, wrapper);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean toBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        String s = o.toString();
        return "true".equalsIgnoreCase(s) || "t".equalsIgnoreCase(s) || "1".equals(s);
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
}
