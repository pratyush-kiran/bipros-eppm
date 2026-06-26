package com.bipros.reporting.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Activity cost aggregation for the AI {@code get_activity_cost} tool. Answers:
 *
 * <ul>
 *   <li>Total cost for an activity (planned / actual / remaining + breakdown by role).
 *   <li>Cost on a specific day for an activity.
 *   <li>Cost attributed to a particular supervisor (DPR-level supervisor, not the activity's
 *       currently-assigned supervisor).
 *   <li>Manpower / equipment / material split.
 * </ul>
 *
 * <p>Planned cost is sourced from {@code resource.resource_assignments.planned_cost} (snapshot
 * at assignment creation). Actual cost canonical source is
 * {@code resource.resource_assignments.actual_cost} — maintained by
 * {@code ResourceAssignmentCostRollupListener} as {@code effective_rate × actual_units}
 * whenever DPRs are submitted or edited. This is exactly what the Resource Plan UI on the
 * activity sidebar displays, so AI answers match the UI.
 *
 * <p>When the caller passes a date window or a supervisor filter, the canonical
 * assignment-level rollup cannot be sliced. The service then computes actual cost from DPR
 * child rows × the matched assignment's effective_rate, UNIONed with material-consumption-log
 * rows that the store keeper logged directly (no DPR) against an activity:
 * <ul>
 *   <li>manpower: {@code SUM(dpr_manpower.nos × a.effective_rate)}
 *   <li>equipment: {@code SUM(dpr_equipment.nos × a.effective_rate)}
 *   <li>material:  {@code SUM(dpr_material.quantity × a.effective_rate)
 *                  + SUM(material_consumption_logs.line_cost
 *                        WHERE activity_id IS NOT NULL AND line_cost IS NOT NULL)}
 * </ul>
 * with {@code a} joined on {@code (activity_id, variant_id)} where {@code variant_id} is
 * whichever of {@code manpower_role_rate_id}/{@code equipment_role_variant_id}/
 * {@code material_role_variant_id} is populated on the DPR row. DAY-basis units are the
 * common case; HOUR-basis rows are approximated as nos × rate for now.
 *
 * <p>DPR {@code line_cost} columns are intentionally not summed — they are unpopulated in the
 * role-rate model (the cost rollup happens at the assignment level, not the DPR-line level).
 * The {@code resource.material_consumption_logs} feed IS summed via its persisted
 * {@code line_cost} column — store-keeper entries don't flow through the assignment rollup,
 * so the only canonical source of their AC is the log row itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityCostQueryService {

  @PersistenceContext private EntityManager em;

  public enum Breakdown { ROLE, DAY, SUPERVISOR, RESOURCE_TYPE, NONE }

  public record ActivityCostRow(
      String dimension,
      String label,
      BigDecimal plannedCost,
      BigDecimal actualCost,
      BigDecimal remainingCost,
      String warning) {}

  /**
   * One entry from the {@code activity_supervisors} join table. Flat model — there is no
   * primary marker, all supervisors are equal. The display name is the snapshot stored when
   * the row was assigned ({@code user_name_snapshot}); it is kept in sync with public.users
   * on every {@code setSupervisors} write.
   */
  public record AssignedSupervisor(UUID userId, String name) {}

  public record ActivityCostReport(
      UUID activityId,
      String activityCode,
      String activityName,
      UUID projectId,
      /** Legacy cache — equals the first entry in {@link #assignedSupervisors}. Kept for back-compat. */
      UUID assignedSupervisorUserId,
      /** Legacy cache — equals the first entry's name. Kept for back-compat. */
      String assignedSupervisorName,
      /** Full multi-supervisor set from {@code activity.activity_supervisors}. Empty when none assigned. */
      List<AssignedSupervisor> assignedSupervisors,
      LocalDate fromDate,
      LocalDate toDate,
      UUID supervisorFilter,
      Breakdown breakdown,
      BigDecimal plannedCost,
      BigDecimal actualCost,
      BigDecimal remainingCost,
      List<ActivityCostRow> rows,
      String warning) {}

  @Transactional(readOnly = true)
  public ActivityCostReport queryByActivityId(
      UUID activityId,
      LocalDate fromDate,
      LocalDate toDate,
      UUID supervisorUserId,
      Breakdown breakdown) {
    if (activityId == null) {
      throw new IllegalArgumentException("activityId is required");
    }
    Object[] header = findActivityHeader(activityId);
    if (header == null) {
      return new ActivityCostReport(
          activityId, null, null, null, null, null, List.of(),
          fromDate, toDate, supervisorUserId,
          breakdown == null ? Breakdown.NONE : breakdown,
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
          List.of(),
          "Activity not found.");
    }
    return build(
        activityId,
        toUuid(header[0]),
        toStr(header[1]),
        toStr(header[2]),
        toUuid(header[3]),
        toStr(header[4]),
        fromDate, toDate, supervisorUserId,
        breakdown == null ? Breakdown.NONE : breakdown);
  }

  @Transactional(readOnly = true)
  public ActivityCostReport queryByActivityCode(
      UUID projectId,
      String activityCode,
      LocalDate fromDate,
      LocalDate toDate,
      UUID supervisorUserId,
      Breakdown breakdown) {
    if (activityCode == null || activityCode.isBlank()) {
      throw new IllegalArgumentException("activityCode is required");
    }
    Object[] header = findActivityByCode(projectId, activityCode.trim());
    if (header == null) {
      return new ActivityCostReport(
          null, activityCode, null, projectId, null, null, List.of(),
          fromDate, toDate, supervisorUserId,
          breakdown == null ? Breakdown.NONE : breakdown,
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
          List.of(),
          "Activity not found for code " + activityCode + " on this project.");
    }
    UUID activityId = toUuid(header[3]);
    return build(
        activityId,
        toUuid(header[0]),
        toStr(header[1]),
        toStr(header[2]),
        toUuid(header[4]),
        toStr(header[5]),
        fromDate, toDate, supervisorUserId,
        breakdown == null ? Breakdown.NONE : breakdown);
  }

  // ───────────────────────── private helpers ─────────────────────────

  private ActivityCostReport build(
      UUID activityId,
      UUID projectId,
      String activityCode,
      String activityName,
      UUID assignedSupervisorUserId,
      String assignedSupervisorName,
      LocalDate fromDate,
      LocalDate toDate,
      UUID supervisorUserId,
      Breakdown breakdown) {

    BigDecimal plannedTotal = sumPlannedCost(activityId);
    BigDecimal actualTotal = sumActualCost(activityId, fromDate, toDate, supervisorUserId);
    BigDecimal remainingTotal = plannedTotal.subtract(actualTotal);
    if (remainingTotal.signum() < 0) remainingTotal = BigDecimal.ZERO;

    List<ActivityCostRow> rows = switch (breakdown) {
      case ROLE -> breakdownByRole(activityId, fromDate, toDate, supervisorUserId);
      case DAY -> breakdownByDay(activityId, fromDate, toDate, supervisorUserId);
      case SUPERVISOR -> breakdownBySupervisor(activityId, fromDate, toDate);
      case RESOURCE_TYPE -> breakdownByResourceType(activityId, fromDate, toDate, supervisorUserId);
      case NONE -> List.of();
    };

    // Load every supervisor on the activity_supervisors join table. The team's setSupervisors
    // flow keeps the legacy cache in sync with the first entry, so the singular fields above
    // remain valid for "primary" / "first" views; this list is the full set.
    List<AssignedSupervisor> assignedSupervisors = loadAssignedSupervisors(activityId);

    String warning = null;
    if (plannedTotal.signum() == 0 && actualTotal.signum() == 0 && breakdown != Breakdown.NONE
        && rows.isEmpty()) {
      warning = "No cost data (planned or actual) for this activity under the given filters.";
    }

    return new ActivityCostReport(
        activityId, activityCode, activityName, projectId,
        assignedSupervisorUserId, assignedSupervisorName, assignedSupervisors,
        fromDate, toDate, supervisorUserId, breakdown,
        plannedTotal, actualTotal, remainingTotal, rows, warning);
  }

  /**
   * Read all supervisors on the activity_supervisors join table for this activity. The
   * display name comes from {@code user_name_snapshot} (kept in sync by setSupervisors),
   * with a fallback to {@code public.users} when the snapshot is missing.
   */
  private List<AssignedSupervisor> loadAssignedSupervisors(UUID activityId) {
    if (activityId == null) return List.of();
    Query q = em.createNativeQuery(
        "SELECT s.user_id, "
            + "       COALESCE(NULLIF(TRIM(s.user_name_snapshot), ''), "
            + "                NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), "
            + "                u.username) AS display_name "
            + "  FROM activity.activity_supervisors s "
            + "  LEFT JOIN public.users u ON u.id = s.user_id "
            + " WHERE s.activity_id = :activityId "
            + " ORDER BY s.created_at ASC, s.user_id ASC");
    q.setParameter("activityId", activityId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    List<AssignedSupervisor> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(new AssignedSupervisor(toUuid(r[0]), toStr(r[1])));
    }
    return out;
  }

  private BigDecimal sumPlannedCost(UUID activityId) {
    Query q = em.createNativeQuery(
        "SELECT COALESCE(SUM(planned_cost), 0) FROM resource.resource_assignments "
            + "WHERE activity_id = :activityId");
    q.setParameter("activityId", activityId);
    return toBigDecimal(q.getSingleResult());
  }

  private BigDecimal sumActualCost(UUID activityId, LocalDate fromDate, LocalDate toDate,
                                   UUID supervisorUserId) {
    if (fromDate == null && toDate == null && supervisorUserId == null) {
      // Canonical: assignment-level rollup (effective_rate × actual_units), the same value
      // the activity sidebar's Resource Plan "Actual Cost" column shows. Store-keeper-only
      // material consumption logs aren't captured in the assignment rollup, so add their
      // line_cost directly.
      Query q = em.createNativeQuery(
          "SELECT COALESCE(SUM(actual_cost), 0) FROM resource.resource_assignments "
              + "WHERE activity_id = :activityId");
      q.setParameter("activityId", activityId);
      BigDecimal assignment = toBigDecimal(q.getSingleResult());
      BigDecimal logs = sumConsumptionLogContrib(activityId, null, null);
      return assignment.add(logs);
    }
    // Filtered: compute per-DPR contribution × matched assignment's effective_rate. The
    // assignment provides the rate; the DPR child row provides the quantity (nos for
    // manpower/equipment, quantity for material). Supervisor-filter excludes the
    // consumption-log feed (it has no supervisor dimension).
    BigDecimal mp = sumDprContribFiltered("dpr_manpower", "manpower_role_rate_id", "nos",
        activityId, fromDate, toDate, supervisorUserId);
    BigDecimal eq = sumDprContribFiltered("dpr_equipment", "equipment_role_variant_id", "nos",
        activityId, fromDate, toDate, supervisorUserId);
    BigDecimal mt = sumDprContribFiltered("dpr_material", "material_role_variant_id", "quantity",
        activityId, fromDate, toDate, supervisorUserId);
    BigDecimal logs = supervisorUserId == null
        ? sumConsumptionLogContrib(activityId, fromDate, toDate)
        : BigDecimal.ZERO;
    return mp.add(eq).add(mt).add(logs);
  }

  /**
   * Store-keeper material consumption logs that were tagged with this activity. Reads the
   * persisted {@code line_cost} (= consumed × unit_rate stamped at log creation time). Skips
   * rows with null activity_id (no cost attribution possible) or null line_cost (rate was
   * missing — those show up as a separate warning on the operations dashboards).
   */
  private BigDecimal sumConsumptionLogContrib(UUID activityId, LocalDate fromDate,
                                              LocalDate toDate) {
    StringBuilder sql = new StringBuilder(
        "SELECT COALESCE(SUM(line_cost), 0) FROM resource.material_consumption_logs "
            + "WHERE activity_id = :activityId AND line_cost IS NOT NULL");
    if (fromDate != null) sql.append(" AND log_date >= :fromDate");
    if (toDate != null) sql.append(" AND log_date <= :toDate");
    Query q = em.createNativeQuery(sql.toString());
    q.setParameter("activityId", activityId);
    if (fromDate != null) q.setParameter("fromDate", fromDate);
    if (toDate != null) q.setParameter("toDate", toDate);
    return toBigDecimal(q.getSingleResult());
  }

  /**
   * Sum of DPR contributions for one resource family (manpower / equipment / material),
   * computed as {@code child.qty × assignment.effective_rate}. The DPR child row is joined
   * to its matching {@code resource_assignments} row on {@code activity_id} plus the
   * variant FK column ({@code manpower_role_rate_id} etc.). DAY-basis units assumed; HOUR
   * basis rows are approximated as nos × rate without an hour multiplier.
   */
  private BigDecimal sumDprContribFiltered(String childTable, String fkCol, String qtyCol,
                                           UUID activityId, LocalDate fromDate, LocalDate toDate,
                                           UUID supervisorUserId) {
    StringBuilder sql = new StringBuilder(
        "SELECT COALESCE(SUM(c." + qtyCol + " * COALESCE(a.effective_rate, 0)), 0) "
            + "FROM project." + childTable + " c "
            + "JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "LEFT JOIN resource.resource_assignments a "
            + "  ON a.activity_id = d.activity_id AND a." + fkCol + " = c." + fkCol + " "
            + "WHERE d.activity_id = :activityId "
            + "AND COALESCE(d.approval_status, 'DRAFT') = 'APPROVED'");
    if (fromDate != null) sql.append(" AND d.report_date >= :fromDate");
    if (toDate != null) sql.append(" AND d.report_date <= :toDate");
    if (supervisorUserId != null) sql.append(" AND d.supervisor_user_id = :supervisorUserId");
    Query q = em.createNativeQuery(sql.toString());
    q.setParameter("activityId", activityId);
    if (fromDate != null) q.setParameter("fromDate", fromDate);
    if (toDate != null) q.setParameter("toDate", toDate);
    if (supervisorUserId != null) q.setParameter("supervisorUserId", supervisorUserId);
    return toBigDecimal(q.getSingleResult());
  }

  private List<ActivityCostRow> breakdownByRole(UUID activityId, LocalDate fromDate,
                                                LocalDate toDate, UUID supervisorUserId) {
    Map<String, BigDecimal> plannedByRole = sumPlannedByRole(activityId);
    boolean filtered = fromDate != null || toDate != null || supervisorUserId != null;
    Map<String, BigDecimal> actualByRole = filtered
        ? sumDprActualByRole(activityId, fromDate, toDate, supervisorUserId)
        : sumAssignmentActualByRole(activityId);
    return mergeBreakdown(plannedByRole, actualByRole, "role", "legacy_dpr_row_no_role_binding");
  }

  private Map<String, BigDecimal> sumPlannedByRole(UUID activityId) {
    Query q = em.createNativeQuery(
        "SELECT COALESCE(r.code, '(unknown)') AS role_code, "
            + "       COALESCE(SUM(a.planned_cost), 0) "
            + "FROM resource.resource_assignments a "
            + "LEFT JOIN resource.resource_roles r ON r.id = a.role_id "
            + "WHERE a.activity_id = :activityId "
            + "GROUP BY r.code");
    q.setParameter("activityId", activityId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (Object[] row : rows) {
      out.put(toStr(row[0]), toBigDecimal(row[1]));
    }
    return out;
  }

  /** Unfiltered actual-cost breakdown — read the assignment-level rollup. */
  private Map<String, BigDecimal> sumAssignmentActualByRole(UUID activityId) {
    Query q = em.createNativeQuery(
        "SELECT COALESCE(r.code, '(unknown)') AS role_code, "
            + "       COALESCE(SUM(a.actual_cost), 0) "
            + "FROM resource.resource_assignments a "
            + "LEFT JOIN resource.resource_roles r ON r.id = a.role_id "
            + "WHERE a.activity_id = :activityId "
            + "GROUP BY r.code");
    q.setParameter("activityId", activityId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (Object[] row : rows) {
      out.put(toStr(row[0]), toBigDecimal(row[1]));
    }
    return out;
  }

  /** Filtered actual-cost breakdown — compute from DPR contributions × assignment rate. */
  private Map<String, BigDecimal> sumDprActualByRole(UUID activityId, LocalDate fromDate,
                                                     LocalDate toDate, UUID supervisorUserId) {
    String filter = buildDateSupervisorFilter(fromDate, toDate, supervisorUserId);
    String childUnion =
        "  SELECT c.role_id, (c.nos * COALESCE(a.effective_rate, 0))::numeric AS contrib "
            + "  FROM project.dpr_manpower c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.manpower_role_rate_id = c.manpower_role_rate_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT c.role_id, (c.nos * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_equipment c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.equipment_role_variant_id = c.equipment_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT c.role_id, (c.quantity * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_material c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.material_role_variant_id = c.material_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter;
    String sql = "SELECT COALESCE(r.code, '(legacy)') AS role_code, COALESCE(SUM(u.contrib), 0) "
        + "FROM (" + childUnion + ") u "
        + "LEFT JOIN resource.resource_roles r ON r.id = u.role_id "
        + "GROUP BY r.code";
    Query q = em.createNativeQuery(sql);
    q.setParameter("activityId", activityId);
    if (fromDate != null) q.setParameter("fromDate", fromDate);
    if (toDate != null) q.setParameter("toDate", toDate);
    if (supervisorUserId != null) q.setParameter("supervisorUserId", supervisorUserId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (Object[] row : rows) {
      out.put(toStr(row[0]), toBigDecimal(row[1]));
    }
    return out;
  }

  /** Per-day breakdown is always DPR-driven (assignment-level rollup has no date dim). */
  private List<ActivityCostRow> breakdownByDay(UUID activityId, LocalDate fromDate,
                                               LocalDate toDate, UUID supervisorUserId) {
    String filter = buildDateSupervisorFilter(fromDate, toDate, supervisorUserId);
    String sql =
        "SELECT u.report_date, COALESCE(SUM(u.contrib), 0) FROM ( "
            + "  SELECT d.report_date, (c.nos * COALESCE(a.effective_rate, 0))::numeric AS contrib "
            + "  FROM project.dpr_manpower c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.manpower_role_rate_id = c.manpower_role_rate_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT d.report_date, (c.nos * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_equipment c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.equipment_role_variant_id = c.equipment_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT d.report_date, (c.quantity * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_material c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.material_role_variant_id = c.material_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + ") u "
            + "GROUP BY u.report_date ORDER BY u.report_date";
    Query q = em.createNativeQuery(sql);
    q.setParameter("activityId", activityId);
    if (fromDate != null) q.setParameter("fromDate", fromDate);
    if (toDate != null) q.setParameter("toDate", toDate);
    if (supervisorUserId != null) q.setParameter("supervisorUserId", supervisorUserId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    List<ActivityCostRow> out = new ArrayList<>();
    for (Object[] row : rows) {
      LocalDate date = (LocalDate) (row[0] instanceof java.sql.Date d ? d.toLocalDate() : row[0]);
      out.add(new ActivityCostRow("day", date.toString(),
          BigDecimal.ZERO, toBigDecimal(row[1]), BigDecimal.ZERO, null));
    }
    return out;
  }

  /** Per-supervisor breakdown is always DPR-driven (supervisor lives on DPR header). */
  private List<ActivityCostRow> breakdownBySupervisor(UUID activityId, LocalDate fromDate,
                                                      LocalDate toDate) {
    String filter = buildDateSupervisorFilter(fromDate, toDate, null);
    String sql =
        "SELECT u.supervisor_user_id, COALESCE(SUM(u.contrib), 0) FROM ( "
            + "  SELECT d.supervisor_user_id, (c.nos * COALESCE(a.effective_rate, 0))::numeric AS contrib "
            + "  FROM project.dpr_manpower c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.manpower_role_rate_id = c.manpower_role_rate_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT d.supervisor_user_id, (c.nos * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_equipment c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.equipment_role_variant_id = c.equipment_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + "  UNION ALL "
            + "  SELECT d.supervisor_user_id, (c.quantity * COALESCE(a.effective_rate, 0))::numeric "
            + "  FROM project.dpr_material c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.material_role_variant_id = c.material_role_variant_id "
            + "   WHERE d.activity_id = :activityId "
            + "     AND COALESCE(d.approval_status,'DRAFT') = 'APPROVED'" + filter
            + ") u "
            + "GROUP BY u.supervisor_user_id ORDER BY 2 DESC";
    Query q = em.createNativeQuery(sql);
    q.setParameter("activityId", activityId);
    if (fromDate != null) q.setParameter("fromDate", fromDate);
    if (toDate != null) q.setParameter("toDate", toDate);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    List<ActivityCostRow> out = new ArrayList<>();
    for (Object[] row : rows) {
      UUID supId = toUuid(row[0]);
      String label = supId != null ? supId.toString() : "(no supervisor)";
      out.add(new ActivityCostRow("supervisor", label,
          BigDecimal.ZERO, toBigDecimal(row[1]), BigDecimal.ZERO, null));
    }
    return out;
  }

  private List<ActivityCostRow> breakdownByResourceType(UUID activityId, LocalDate fromDate,
                                                        LocalDate toDate, UUID supervisorUserId) {
    boolean filtered = fromDate != null || toDate != null || supervisorUserId != null;
    BigDecimal mp;
    BigDecimal eq;
    BigDecimal mt;
    if (filtered) {
      mp = sumDprContribFiltered("dpr_manpower", "manpower_role_rate_id", "nos",
          activityId, fromDate, toDate, supervisorUserId);
      eq = sumDprContribFiltered("dpr_equipment", "equipment_role_variant_id", "nos",
          activityId, fromDate, toDate, supervisorUserId);
      mt = sumDprContribFiltered("dpr_material", "material_role_variant_id", "quantity",
          activityId, fromDate, toDate, supervisorUserId);
      if (supervisorUserId == null) {
        mt = mt.add(sumConsumptionLogContrib(activityId, fromDate, toDate));
      }
    } else {
      // Canonical: split assignment.actual_cost by which variant FK is non-null.
      mp = sumAssignmentActualForVariantFk(activityId, "manpower_role_rate_id");
      eq = sumAssignmentActualForVariantFk(activityId, "equipment_role_variant_id");
      mt = sumAssignmentActualForVariantFk(activityId, "material_role_variant_id")
          .add(sumConsumptionLogContrib(activityId, null, null));
    }
    List<ActivityCostRow> out = new ArrayList<>();
    out.add(new ActivityCostRow("resource_type", "MANPOWER", BigDecimal.ZERO, mp, BigDecimal.ZERO, null));
    out.add(new ActivityCostRow("resource_type", "EQUIPMENT", BigDecimal.ZERO, eq, BigDecimal.ZERO, null));
    out.add(new ActivityCostRow("resource_type", "MATERIAL", BigDecimal.ZERO, mt, BigDecimal.ZERO, null));
    return out;
  }

  private BigDecimal sumAssignmentActualForVariantFk(UUID activityId, String fkCol) {
    Query q = em.createNativeQuery(
        "SELECT COALESCE(SUM(actual_cost), 0) FROM resource.resource_assignments "
            + "WHERE activity_id = :activityId AND " + fkCol + " IS NOT NULL");
    q.setParameter("activityId", activityId);
    return toBigDecimal(q.getSingleResult());
  }

  private String buildDateSupervisorFilter(LocalDate fromDate, LocalDate toDate,
                                           UUID supervisorUserId) {
    StringBuilder sb = new StringBuilder();
    if (fromDate != null) sb.append(" AND d.report_date >= :fromDate");
    if (toDate != null) sb.append(" AND d.report_date <= :toDate");
    if (supervisorUserId != null) sb.append(" AND d.supervisor_user_id = :supervisorUserId");
    return sb.toString();
  }

  private List<ActivityCostRow> mergeBreakdown(Map<String, BigDecimal> planned,
                                               Map<String, BigDecimal> actual,
                                               String dimension,
                                               String legacyWarning) {
    Map<String, ActivityCostRow> merged = new LinkedHashMap<>();
    planned.forEach((k, v) -> merged.put(k, new ActivityCostRow(dimension, k, v,
        actual.getOrDefault(k, BigDecimal.ZERO),
        v.subtract(actual.getOrDefault(k, BigDecimal.ZERO)).max(BigDecimal.ZERO),
        null)));
    for (Map.Entry<String, BigDecimal> e : actual.entrySet()) {
      if (merged.containsKey(e.getKey())) continue;
      String w = "(legacy)".equals(e.getKey()) || "(unknown)".equals(e.getKey())
          ? legacyWarning : null;
      merged.put(e.getKey(),
          new ActivityCostRow(dimension, e.getKey(), BigDecimal.ZERO, e.getValue(),
              BigDecimal.ZERO, w));
    }
    return new ArrayList<>(merged.values());
  }

  // ───────────────────── lookups ─────────────────────

  private Object[] findActivityHeader(UUID activityId) {
    Query q = em.createNativeQuery(
        "SELECT project_id, code, name, supervisor_user_id, supervisor_user_name "
            + "FROM activity.activities WHERE id = :id");
    q.setParameter("id", activityId);
    @SuppressWarnings("unchecked")
    List<Object[]> rs = q.getResultList();
    return rs.isEmpty() ? null : rs.get(0);
  }

  private Object[] findActivityByCode(UUID projectId, String code) {
    String sql = "SELECT project_id, code, name, id, supervisor_user_id, supervisor_user_name "
        + "FROM activity.activities WHERE code = :code"
        + (projectId != null ? " AND project_id = :projectId" : "")
        + " LIMIT 1";
    Query q = em.createNativeQuery(sql);
    q.setParameter("code", code);
    if (projectId != null) q.setParameter("projectId", projectId);
    @SuppressWarnings("unchecked")
    List<Object[]> rs = q.getResultList();
    return rs.isEmpty() ? null : rs.get(0);
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal b) return b;
    if (o instanceof Number n) return new BigDecimal(n.toString());
    return new BigDecimal(o.toString());
  }

  private static String toStr(Object o) {
    return o == null ? null : o.toString();
  }

  private static UUID toUuid(Object o) {
    if (o == null) return null;
    if (o instanceof UUID u) return u;
    try {
      return UUID.fromString(o.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
