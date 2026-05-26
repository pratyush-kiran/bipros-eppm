package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.CapacityUtilizationReport.Budgeted;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison.EquipmentDelta;
import com.bipros.reporting.application.dto.SupervisorPerformanceComparison.TradeDelta;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ActivityDrillDown;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.EquipmentRollup;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PeriodMetrics;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PeriodMetricsBuckets;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PlannedActuals;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.PlannedActualsBuckets;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ProductivityNorms;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.ResourceLine;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.Summary;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.TradeRollup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supervisor-aware capacity utilization. Builds the SC180 Resource Productivity Report shape
 * (per-trade Manpower Utilization, per-equipment-type Equipment Utilization, per-activity
 * drill-down) by reading {@code project.dpr_manpower} / {@code project.dpr_equipment} directly
 * — keyed by supervisor via the parent {@code daily_progress_reports.supervisor_user_id}
 * (Phase 4.4: the legacy {@code supervisor_resource_id} column was dropped by migration 091).
 *
 * <p>{@code supervisorUserId == null} collapses the filter to project-wide. The page can use
 * the same endpoint for both views.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupervisorPerformanceReportService {

  private static final double DEFAULT_HOURS_PER_DAY = 8.0;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal UTILIZATION_CAP = BigDecimal.valueOf(999);

  @PersistenceContext private EntityManager em;

  private final ProductivityNormResolver normResolver;

  // ─── Public API ─────────────────────────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public SupervisorPerformanceReport build(
      UUID projectId, UUID supervisorUserId,
      LocalDate fromDate, LocalDate toDate, int workDays) {

    LocalDate today = LocalDate.now();
    LocalDate effectiveTo = toDate == null ? today : toDate;
    LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfMonth(1) : fromDate;
    int effectiveWorkDays = workDays > 0 ? workDays : 26;
    // Day / CalendarMonth anchor: today when today falls inside the window, otherwise
    // effectiveTo (the last day of the window). Same rule as CapacityUtilizationReportService.
    LocalDate referenceDate = effectiveTo.isBefore(today) ? effectiveTo : today;
    if (referenceDate.isBefore(effectiveFrom)) referenceDate = effectiveFrom;
    YearMonth referenceMonth = YearMonth.from(referenceDate);

    String supervisorName = supervisorUserId == null ? null
        : resolveSupervisorName(projectId, supervisorUserId);

    List<ManpowerCellRow> manpowerCells =
        fetchManpowerCells(projectId, supervisorUserId, effectiveFrom, effectiveTo);
    List<EquipmentCellRow> equipmentCells =
        fetchEquipmentCells(projectId, supervisorUserId, effectiveFrom, effectiveTo);
    Map<UUID, ActivityMeta> activityMeta =
        fetchActivityMeta(projectId, supervisorUserId, effectiveFrom, effectiveTo,
            referenceDate, referenceMonth);

    // Resolve norms once per (workActivityId, resourceTypeId) to avoid hammering the DB inside
    // the inner loops. Both the trade rollup and the activity drill-down consume the same map.
    // Cache norms with kind-aware keys so a MANPOWER lookup and an EQUIPMENT lookup for the
    // same (work_activity, resource_type) don't collide and don't accidentally cross-apply.
    Map<NormCacheKey, Budgeted> normCache = new HashMap<>();
    for (ManpowerCellRow c : manpowerCells) {
      normCache.computeIfAbsent(
          new NormCacheKey(c.workActivityId(), c.roleId(), c.resourceTypeId(), "MANPOWER"),
          k -> normResolver.resolveByRoleOrType(
              k.workActivityId(), k.roleId(), k.resourceTypeId(), k.normType()));
    }
    for (EquipmentCellRow c : equipmentCells) {
      normCache.computeIfAbsent(
          new NormCacheKey(c.workActivityId(), c.roleId(), c.resourceTypeId(), "EQUIPMENT"),
          k -> normResolver.resolveByRoleOrType(
              k.workActivityId(), k.roleId(), k.resourceTypeId(), k.normType()));
    }

    // Real per-(activity, role) planned headcount from RoleAssignment, used by the drill-down's
    // PLAN column. The Summary section above already uses this on the top-level rollups; the
    // drill-down used to fake "plan = budget" before this fix.
    Map<ActivityRoleKey, BigDecimal> plannedByActivityRole = loadPlannedUnitsByActivityRole(
        projectId, manpowerCells, equipmentCells);

    // Per-(DPR, activity) expected-output map for each side, used by the allocator to decide
    // hide/split. Computed once and shared between manpower and equipment rollups so both
    // sides see the same "other side expected" denominator.
    Map<DprActivityKey, BigDecimal> manpowerExpected =
        computeSideExpectedPerDpr(manpowerCells, normCache, "MANPOWER");
    Map<DprActivityKey, BigDecimal> equipmentExpected =
        computeSideExpectedPerDpr(equipmentCells, normCache, "EQUIPMENT");

    // Norm combination per activity — single SQL fetch covering every activity that appears in
    // either side's cells, so the allocator branches correctly under SERIES/PARALLEL/SUBSTITUTE.
    Map<UUID, String> normCombosByActivity = loadNormCombinations(
        collectActivityIds(manpowerCells, equipmentCells));

    // Sub-contractor qty per DPR — subtracted from qty_executed so the allocator only sees
    // the company-resource portion. See CapacityUtilizationReportService for the same semantics.
    Map<UUID, BigDecimal> subContractorQtyByDpr = loadSubContractorQtyByDpr(
        projectId, effectiveFrom, effectiveTo, supervisorUserId);

    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> manpowerHidden = new ArrayList<>();
    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> equipmentHidden = new ArrayList<>();

    // === Cumulative pass — produces the canonical report. Hidden notes only come from this pass. ===
    List<TradeRollup> tradeRollupsCum = rollUpManpower(
        manpowerCells, equipmentExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        manpowerHidden, effectiveWorkDays);
    List<EquipmentRollup> equipmentRollupsCum = rollUpEquipment(
        equipmentCells, manpowerExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        equipmentHidden, effectiveWorkDays);
    List<ActivityDrillDown> drillDownCum = buildDrillDown(
        manpowerCells, equipmentCells, normCache, activityMeta,
        plannedByActivityRole, manpowerExpected, equipmentExpected, normCombosByActivity,
        subContractorQtyByDpr, effectiveWorkDays);

    // === Day-filtered pass ===
    LocalDate refDay = referenceDate;
    List<ManpowerCellRow> mpCellsDay = manpowerCells.stream()
        .filter(c -> c.reportDate() != null && c.reportDate().equals(refDay))
        .toList();
    List<EquipmentCellRow> eqCellsDay = equipmentCells.stream()
        .filter(c -> c.reportDate() != null && c.reportDate().equals(refDay))
        .toList();
    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> sinkA = new ArrayList<>();
    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> sinkB = new ArrayList<>();
    List<TradeRollup> tradeRollupsDay = rollUpManpower(
        mpCellsDay, equipmentExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        sinkA, effectiveWorkDays);
    List<EquipmentRollup> equipmentRollupsDay = rollUpEquipment(
        eqCellsDay, manpowerExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        sinkB, effectiveWorkDays);
    List<ActivityDrillDown> drillDownDay = buildDrillDown(
        mpCellsDay, eqCellsDay, normCache, activityMeta,
        plannedByActivityRole, manpowerExpected, equipmentExpected, normCombosByActivity,
        subContractorQtyByDpr, effectiveWorkDays);

    // === CalendarMonth-filtered pass ===
    LocalDate monthStart = referenceMonth.atDay(1);
    LocalDate monthEnd = referenceMonth.atEndOfMonth();
    List<ManpowerCellRow> mpCellsMonth = manpowerCells.stream()
        .filter(c -> c.reportDate() != null
                && !c.reportDate().isBefore(monthStart)
                && !c.reportDate().isAfter(monthEnd))
        .toList();
    List<EquipmentCellRow> eqCellsMonth = equipmentCells.stream()
        .filter(c -> c.reportDate() != null
                && !c.reportDate().isBefore(monthStart)
                && !c.reportDate().isAfter(monthEnd))
        .toList();
    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> sinkC = new ArrayList<>();
    List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> sinkD = new ArrayList<>();
    List<TradeRollup> tradeRollupsMonth = rollUpManpower(
        mpCellsMonth, equipmentExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        sinkC, effectiveWorkDays);
    List<EquipmentRollup> equipmentRollupsMonth = rollUpEquipment(
        eqCellsMonth, manpowerExpected, normCache, normCombosByActivity, subContractorQtyByDpr,
        sinkD, effectiveWorkDays);
    List<ActivityDrillDown> drillDownMonth = buildDrillDown(
        mpCellsMonth, eqCellsMonth, normCache, activityMeta,
        plannedByActivityRole, manpowerExpected, equipmentExpected, normCombosByActivity,
        subContractorQtyByDpr, effectiveWorkDays);

    // === Stitch buckets onto the cumulative output ===
    List<TradeRollup> tradeRollups = attachTradeBuckets(
        tradeRollupsCum, tradeRollupsDay, tradeRollupsMonth);
    List<EquipmentRollup> equipmentRollups = attachEquipmentBuckets(
        equipmentRollupsCum, equipmentRollupsDay, equipmentRollupsMonth);
    List<ActivityDrillDown> drillDown = attachActivityBuckets(
        drillDownCum, drillDownDay, drillDownMonth, activityMeta);

    return new SupervisorPerformanceReport(
        projectId, supervisorUserId, supervisorName,
        effectiveFrom, effectiveTo, effectiveWorkDays,
        referenceDate,
        new Summary(tradeRollups, equipmentRollups, manpowerHidden, equipmentHidden),
        drillDown);
  }

  // ─── 3-pass bucket stitchers ───────────────────────────────────────────────────────────────

  private static PeriodMetrics toMetrics(TradeRollup t) {
    if (t == null) return null;
    return new PeriodMetrics(t.qtyDone(), t.budgetedManDays(), t.actualManDays(),
        t.actualDaysOnHiddenSides(), t.actualDaysUntracked(),
        t.utilizationPct(), t.costImplication());
  }

  private static PeriodMetrics toMetrics(EquipmentRollup e) {
    if (e == null) return null;
    return new PeriodMetrics(e.qtyDone(), e.budgetedDays(), e.actualDays(),
        e.actualDaysOnHiddenSides(), e.actualDaysUntracked(),
        e.utilizationPct(), e.costImplication());
  }

  private static List<TradeRollup> attachTradeBuckets(
      List<TradeRollup> cumulative, List<TradeRollup> day, List<TradeRollup> month) {
    Map<String, TradeRollup> dayMap = new HashMap<>();
    for (TradeRollup t : day) dayMap.put(t.tradeKey(), t);
    Map<String, TradeRollup> monthMap = new HashMap<>();
    for (TradeRollup t : month) monthMap.put(t.tradeKey(), t);
    List<TradeRollup> out = new ArrayList<>(cumulative.size());
    for (TradeRollup c : cumulative) {
      PeriodMetricsBuckets buckets = new PeriodMetricsBuckets(
          toMetrics(dayMap.get(c.tradeKey())),
          toMetrics(monthMap.get(c.tradeKey())),
          toMetrics(c));
      out.add(new TradeRollup(
          c.tradeKey(), c.tradeLabel(), c.mmRate(), c.qtyDone(),
          c.budgetedManDays(), c.actualManDays(),
          c.actualDaysOnHiddenSides(), c.actualDaysUntracked(),
          c.utilizationPct(), c.costImplication(), c.normSource(),
          buckets));
    }
    return out;
  }

  private static List<EquipmentRollup> attachEquipmentBuckets(
      List<EquipmentRollup> cumulative, List<EquipmentRollup> day, List<EquipmentRollup> month) {
    Map<String, EquipmentRollup> dayMap = new HashMap<>();
    for (EquipmentRollup e : day) dayMap.put(e.equipmentKey(), e);
    Map<String, EquipmentRollup> monthMap = new HashMap<>();
    for (EquipmentRollup e : month) monthMap.put(e.equipmentKey(), e);
    List<EquipmentRollup> out = new ArrayList<>(cumulative.size());
    for (EquipmentRollup c : cumulative) {
      PeriodMetricsBuckets buckets = new PeriodMetricsBuckets(
          toMetrics(dayMap.get(c.equipmentKey())),
          toMetrics(monthMap.get(c.equipmentKey())),
          toMetrics(c));
      out.add(new EquipmentRollup(
          c.equipmentKey(), c.equipmentLabel(), c.hourRate(), c.qtyDone(),
          c.budgetedDays(), c.actualDays(),
          c.actualDaysOnHiddenSides(), c.actualDaysUntracked(),
          c.utilizationPct(), c.costImplication(), c.normSource(),
          buckets));
    }
    return out;
  }

  private static List<ActivityDrillDown> attachActivityBuckets(
      List<ActivityDrillDown> cumulative, List<ActivityDrillDown> day,
      List<ActivityDrillDown> month, Map<UUID, ActivityMeta> activityMeta) {
    // Index day/month drill-downs by activityId for fast lookup.
    Map<UUID, ActivityDrillDown> dayByAct = new HashMap<>();
    for (ActivityDrillDown a : day) dayByAct.put(a.activityId(), a);
    Map<UUID, ActivityDrillDown> monthByAct = new HashMap<>();
    for (ActivityDrillDown a : month) monthByAct.put(a.activityId(), a);

    List<ActivityDrillDown> out = new ArrayList<>(cumulative.size());
    for (ActivityDrillDown c : cumulative) {
      ActivityDrillDown dayA = dayByAct.get(c.activityId());
      ActivityDrillDown monthA = monthByAct.get(c.activityId());
      // For each resource line in cumulative, find matching lines in day/month by (kind, key)
      // and build PlannedActualsBuckets. Day/month lines may be absent (no contribution in that
      // bucket) — emit null bucket leaves rather than blanks so the AI can distinguish.
      Map<String, ResourceLine> dayLines = indexLines(dayA);
      Map<String, ResourceLine> monthLines = indexLines(monthA);
      List<ResourceLine> stitched = new ArrayList<>(c.resources().size());
      for (ResourceLine cr : c.resources()) {
        String key = cr.kind() + "::" + cr.resourceKey();
        ResourceLine dr = dayLines.get(key);
        ResourceLine mr = monthLines.get(key);
        PlannedActualsBuckets planBuckets = new PlannedActualsBuckets(
            dr == null ? null : dr.planMonth(),
            mr == null ? null : mr.planMonth(),
            cr.planMonth());
        PlannedActualsBuckets actualBuckets = new PlannedActualsBuckets(
            dr == null ? null : dr.actualMonth(),
            mr == null ? null : mr.actualMonth(),
            cr.actualMonth());
        stitched.add(new ResourceLine(cr.kind(), cr.resourceKey(), cr.resourceLabel(),
            cr.norms(), cr.planMonth(), cr.actualMonth(), planBuckets, actualBuckets));
      }
      // Pull bucket-anchored qty totals from the enriched ActivityMeta (single source of truth
      // for activity-level qty per bucket — the filtered drill-down passes reuse the same
      // cumulative meta so their qty field would otherwise be wrong).
      ActivityMeta meta = activityMeta == null ? null : activityMeta.get(c.activityId());
      out.add(new ActivityDrillDown(
          c.activityId(), c.activityCode(), c.activityName(), c.unit(),
          c.qtyForMonth(),                                     // cumulative-window (legacy field)
          meta != null ? meta.qtyForDay() : null,              // single-day anchor
          meta != null ? meta.qtyForCalendarMonth() : null,    // calendar month of anchor
          c.subContractorQty(),
          stitched,
          c.remarks()));
    }
    return out;
  }

  private static Map<String, ResourceLine> indexLines(ActivityDrillDown a) {
    Map<String, ResourceLine> out = new HashMap<>();
    if (a == null) return out;
    for (ResourceLine rl : a.resources()) {
      out.put(rl.kind() + "::" + rl.resourceKey(), rl);
    }
    return out;
  }

  /** Sum of (resolvedNorm × actualNos) per (DPR, activity) on one side. Null/zero norms are
   *  treated as 0 — same convention as {@link CapacityAllocator.RoleInput#expectedContribution}. */
  private Map<DprActivityKey, BigDecimal> computeSideExpectedPerDpr(
      List<? extends Object> cells, Map<NormCacheKey, Budgeted> normCache, String normType) {
    Map<DprActivityKey, BigDecimal> out = new HashMap<>();
    for (Object o : cells) {
      UUID dprId, activityId, workActivityId, roleId, resourceTypeId;
      BigDecimal actualNos;
      if (o instanceof ManpowerCellRow m) {
        dprId = m.dprId(); activityId = m.activityId();
        workActivityId = m.workActivityId(); roleId = m.roleId();
        resourceTypeId = m.resourceTypeId(); actualNos = m.actualNos();
      } else if (o instanceof EquipmentCellRow e) {
        dprId = e.dprId(); activityId = e.activityId();
        workActivityId = e.workActivityId(); roleId = e.roleId();
        resourceTypeId = e.resourceTypeId(); actualNos = e.actualNos();
      } else {
        continue;
      }
      if (dprId == null || activityId == null) continue;
      Budgeted norm = normCache.get(new NormCacheKey(workActivityId, roleId, resourceTypeId, normType));
      if (norm == null || norm.outputPerDay() == null || norm.outputPerDay().signum() <= 0) continue;
      if (actualNos == null || actualNos.signum() <= 0) continue;
      BigDecimal contrib = norm.outputPerDay().multiply(actualNos);
      out.merge(new DprActivityKey(dprId, activityId), contrib, BigDecimal::add);
    }
    return out;
  }

  private java.util.Set<UUID> collectActivityIds(
      List<ManpowerCellRow> m, List<EquipmentCellRow> e) {
    java.util.Set<UUID> ids = new java.util.HashSet<>();
    for (ManpowerCellRow c : m) if (c.activityId() != null) ids.add(c.activityId());
    for (EquipmentCellRow c : e) if (c.activityId() != null) ids.add(c.activityId());
    return ids;
  }

  /** Σ {@code dpr_sub_contractor.quantity} per DPR within the window + supervisor filter.
   *  Subtracted from each DPR's qty_executed before the allocator distributes work to roles. */
  @SuppressWarnings("unchecked")
  private Map<UUID, BigDecimal> loadSubContractorQtyByDpr(
      UUID projectId, LocalDate fromDate, LocalDate toDate, UUID supervisorUserId) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT d.id, COALESCE(SUM(sc.quantity), 0) "
                + "FROM project.daily_progress_reports d "
                + "JOIN project.dpr_sub_contractor sc ON sc.dpr_id = d.id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY d.id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();
    Map<UUID, BigDecimal> out = new HashMap<>();
    for (Object[] r : rows) {
      UUID id = (UUID) r[0];
      BigDecimal qty = toBigDecimal(r[1]);
      if (id != null && qty != null) out.put(id, qty);
    }
    return out;
  }

  /** Bulk-fetch {@code wa.norm_combination} for the given activities. Falls back to SERIES per
   *  activity when null/empty (matches {@link CapacityUtilizationReportService}). */
  @SuppressWarnings("unchecked")
  private Map<UUID, String> loadNormCombinations(java.util.Set<UUID> activityIds) {
    if (activityIds == null || activityIds.isEmpty()) return Map.of();
    List<Object[]> rows = em.createNativeQuery(
            "SELECT a.id, COALESCE(wa.norm_combination, 'SERIES') "
                + "FROM activity.activities a "
                + "JOIN resource.work_activities wa ON wa.id = a.work_activity_id "
                + "WHERE a.id IN :ids")
        .setParameter("ids", activityIds)
        .getResultList();
    Map<UUID, String> out = new HashMap<>();
    for (Object[] r : rows) {
      UUID id = (UUID) r[0];
      String combo = r[1] == null ? "SERIES" : r[1].toString();
      out.put(id, combo);
    }
    return out;
  }

  @Transactional(readOnly = true)
  public SupervisorPerformanceComparison compare(
      UUID projectId, List<UUID> supervisorUserIds,
      LocalDate fromDate, LocalDate toDate, int workDays) {
    if (supervisorUserIds == null || supervisorUserIds.size() < 2) {
      throw new IllegalArgumentException("compare requires at least 2 supervisor ids");
    }

    List<SupervisorPerformanceReport> reports = new ArrayList<>(supervisorUserIds.size());
    for (UUID supId : supervisorUserIds) {
      reports.add(build(projectId, supId, fromDate, toDate, workDays));
    }

    LocalDate windowFrom = reports.get(0).fromDate();
    LocalDate windowTo = reports.get(0).toDate();
    int windowWorkDays = reports.get(0).workDays();

    Map<String, TradeDelta> tradeAcc = new LinkedHashMap<>();
    for (SupervisorPerformanceReport rep : reports) {
      for (TradeRollup tr : rep.summary().manpower()) {
        TradeDelta delta = tradeAcc.computeIfAbsent(tr.tradeKey(),
            k -> new TradeDelta(k, tr.tradeLabel(), new LinkedHashMap<>(), null, null));
        delta.bySupervisor().put(rep.supervisorUserId(), tr);
      }
    }
    List<TradeDelta> tradeDeltas = new ArrayList<>(tradeAcc.size());
    for (TradeDelta partial : tradeAcc.values()) {
      Map.Entry<UUID, TradeRollup> best = partial.bySupervisor().entrySet().stream()
          .filter(e -> e.getValue().utilizationPct() != null)
          .max(Comparator.comparing(e -> e.getValue().utilizationPct()))
          .orElse(null);
      tradeDeltas.add(new TradeDelta(
          partial.tradeKey(), partial.tradeLabel(), partial.bySupervisor(),
          best != null ? best.getValue().utilizationPct() : null,
          best != null ? best.getKey() : null));
    }

    Map<String, EquipmentDelta> eqAcc = new LinkedHashMap<>();
    for (SupervisorPerformanceReport rep : reports) {
      for (EquipmentRollup er : rep.summary().equipment()) {
        EquipmentDelta delta = eqAcc.computeIfAbsent(er.equipmentKey(),
            k -> new EquipmentDelta(k, er.equipmentLabel(), new LinkedHashMap<>(), null, null));
        delta.bySupervisor().put(rep.supervisorUserId(), er);
      }
    }
    List<EquipmentDelta> equipmentDeltas = new ArrayList<>(eqAcc.size());
    for (EquipmentDelta partial : eqAcc.values()) {
      Map.Entry<UUID, EquipmentRollup> best = partial.bySupervisor().entrySet().stream()
          .filter(e -> e.getValue().utilizationPct() != null)
          .max(Comparator.comparing(e -> e.getValue().utilizationPct()))
          .orElse(null);
      equipmentDeltas.add(new EquipmentDelta(
          partial.equipmentKey(), partial.equipmentLabel(), partial.bySupervisor(),
          best != null ? best.getValue().utilizationPct() : null,
          best != null ? best.getKey() : null));
    }

    return new SupervisorPerformanceComparison(
        projectId, windowFrom, windowTo, windowWorkDays, reports, tradeDeltas, equipmentDeltas);
  }

  // ─── Native queries (Q1, Q2, Q3) ────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private List<ManpowerCellRow> fetchManpowerCells(
      UUID projectId, UUID supervisorUserId, LocalDate fromDate, LocalDate toDate) {

    // Role-only model: groups by m.role_id (the trade) and joins resource_roles directly.
    // Legacy resource-keyed rows (m.resource_id set, m.role_id NULL) are skipped — they don't
    // exist in the post-migration data path. working_hours falls back to 8 hrs/day so DPRs
    // that capture only headcount (nos) still produce correct man-days (= nos × 8 / 8 = nos).
    // line_cost is computed from the manpower_role_rate variant the DPR pinned, so MM Rate
    // resolves correctly even when m.unit_rate / m.line_cost weren't stamped at save time.
    //
    // d.id (dpr_id) is in the GROUP BY so each row is one (DPR × activity × role) triple — the
    // allocator needs per-DPR granularity to decide hide/split per the activity's norm
    // combination (SERIES / PARALLEL / SUBSTITUTE).
    List<Object[]> raw = em.createNativeQuery(
            "SELECT "
                + "  rr.code                                                       AS trade_key, "
                + "  rr.name                                                       AS trade_label, "
                + "  rr.resource_type_id                                           AS resource_type_id, "
                + "  d.activity_id                                                 AS activity_id, "
                + "  a.work_activity_id                                            AS work_activity_id, "
                + "  a.code                                                        AS activity_code, "
                + "  a.name                                                        AS activity_name, "
                + "  d.unit                                                        AS activity_unit, "
                + "  MAX(d.qty_executed)                                           AS qty, "
                // actual_nos is the raw sum of headcount. working_hours is DPR logging metadata
                // only — never multiplied in. DAY-basis manpower rates are paid per person/day
                // regardless of hours, and half-day attendance should not silently halve the
                // role's contribution to Capacity Util / Supervisor Performance.
                + "  SUM(COALESCE(m.nos, 0))                                       AS actual_nos, "
                + "  SUM(COALESCE(m.nos, 0) * COALESCE(m.unit_rate, mrr.rate, 0))  AS line_cost_total, "
                + "  m.role_id                                                     AS role_id, "
                + "  d.id                                                          AS dpr_id, "
                + "  d.report_date                                                 AS report_date "
                + "FROM project.daily_progress_reports d "
                + "JOIN project.dpr_manpower m       ON m.dpr_id = d.id "
                + "JOIN resource.resource_roles rr  ON rr.id = m.role_id "
                + "LEFT JOIN resource.manpower_role_rates mrr ON mrr.id = m.manpower_role_rate_id "
                + "LEFT JOIN activity.activities a   ON a.id = d.activity_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND m.role_id IS NOT NULL "
                // Filter by the supervisor who actually filed the DPR. Co-supervisors on a
                // shared activity each see only their own DPRs — matches the supervisor-dropdown
                // count semantics and the user's mental model. NULL = project-wide.
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY rr.code, rr.name, rr.resource_type_id, d.activity_id, "
                + "         a.work_activity_id, a.code, a.name, d.unit, m.role_id, d.id, d.report_date")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();

    List<ManpowerCellRow> out = new ArrayList<>(raw.size());
    for (Object[] r : raw) {
      out.add(new ManpowerCellRow(
          (String) r[0], (String) r[1], (UUID) r[2],
          (UUID) r[3], (UUID) r[4],
          (String) r[5], (String) r[6], (String) r[7],
          toBigDecimal(r[8]), toBigDecimal(r[9]), toBigDecimal(r[10]),
          (UUID) r[11], (UUID) r[12],
          toLocalDate(r[13])));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<EquipmentCellRow> fetchEquipmentCells(
      UUID projectId, UUID supervisorUserId, LocalDate fromDate, LocalDate toDate) {

    // Same role-only rewrite as the manpower query: group by e.role_id, fall back hours to 8,
    // and join equipment_role_variants for the line-cost rate so MM/Eq Rate appears even when
    // the DPR row didn't snapshot unit_rate at save time.
    //
    // d.id (dpr_id) is in the GROUP BY for the same reason as manpower — allocator needs
    // per-DPR granularity.
    List<Object[]> raw = em.createNativeQuery(
            "SELECT "
                + "  rr.code                                                      AS equipment_key, "
                + "  rr.name                                                      AS equipment_label, "
                + "  rr.resource_type_id                                          AS resource_type_id, "
                + "  d.activity_id                                                AS activity_id, "
                + "  a.work_activity_id                                           AS work_activity_id, "
                + "  a.code                                                       AS activity_code, "
                + "  a.name                                                       AS activity_name, "
                + "  d.unit                                                       AS activity_unit, "
                + "  MAX(d.qty_executed)                                          AS qty, "
                // Same hours-ignored rule as manpower above — equipment actuals = raw sum of nos.
                + "  SUM(COALESCE(e.nos, 0))                                      AS actual_nos, "
                + "  SUM(COALESCE(e.nos, 0) * COALESCE(e.unit_rate, erv.rate, 0)) AS line_cost_total, "
                + "  e.role_id                                                    AS role_id, "
                + "  d.id                                                         AS dpr_id, "
                + "  d.report_date                                                AS report_date "
                + "FROM project.daily_progress_reports d "
                + "JOIN project.dpr_equipment e         ON e.dpr_id = d.id "
                + "JOIN resource.resource_roles rr     ON rr.id = e.role_id "
                + "LEFT JOIN resource.equipment_role_variants erv ON erv.id = e.equipment_role_variant_id "
                + "LEFT JOIN activity.activities a     ON a.id = d.activity_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND e.role_id IS NOT NULL "
                // Same filer-based filter as the manpower query above.
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY rr.code, rr.name, rr.resource_type_id, d.activity_id, "
                + "         a.work_activity_id, a.code, a.name, d.unit, e.role_id, d.id, d.report_date")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();

    List<EquipmentCellRow> out = new ArrayList<>(raw.size());
    for (Object[] r : raw) {
      out.add(new EquipmentCellRow(
          (String) r[0], (String) r[1], (UUID) r[2],
          (UUID) r[3], (UUID) r[4],
          (String) r[5], (String) r[6], (String) r[7],
          toBigDecimal(r[8]), toBigDecimal(r[9]), toBigDecimal(r[10]),
          (UUID) r[11], (UUID) r[12],
          toLocalDate(r[13])));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, ActivityMeta> fetchActivityMeta(
      UUID projectId, UUID supervisorUserId, LocalDate fromDate, LocalDate toDate,
      LocalDate referenceDate, YearMonth referenceMonth) {

    // LEFT JOIN to dpr_sub_contractor + SUM gives Σ sub-contractor qty per activity in the
    // window. Surfaces as the "(X sub-contractor)" leg of the drill-down header breakdown.
    // qty_for_day / qty_for_calendar_month — conditional sums anchored on the report's
    // referenceDate so the AI can answer "qty for the day" vs "for the month" vs "cumulative"
    // without re-calling with narrower windows.
    LocalDate monthStart = referenceMonth.atDay(1);
    LocalDate monthEnd = referenceMonth.atEndOfMonth();
    List<Object[]> raw = em.createNativeQuery(
            "SELECT d.activity_id, "
                + "       MAX(d.activity_name)                                    AS activity_name, "
                + "       MAX(d.unit)                                             AS unit, "
                + "       SUM(d.qty_executed)                                     AS qty_total, "
                + "       SUM(CASE WHEN d.report_date = :referenceDate "
                + "                THEN COALESCE(d.qty_executed, 0) ELSE 0 END)   AS qty_for_day, "
                + "       SUM(CASE WHEN d.report_date BETWEEN :monthStart AND :monthEnd "
                + "                THEN COALESCE(d.qty_executed, 0) ELSE 0 END)   AS qty_for_month, "
                + "       COALESCE(SUM(sc_total.sub_qty), 0)                      AS sub_total, "
                + "       STRING_AGG(DISTINCT NULLIF(d.remarks, ''), ' | ')       AS remarks "
                + "FROM project.daily_progress_reports d "
                + "LEFT JOIN activity.activities a ON a.id = d.activity_id "
                + "LEFT JOIN ( "
                + "    SELECT sc.dpr_id, SUM(COALESCE(sc.quantity, 0)) AS sub_qty "
                + "    FROM project.dpr_sub_contractor sc GROUP BY sc.dpr_id "
                + ") sc_total ON sc_total.dpr_id = d.id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND d.activity_id IS NOT NULL "
                // Same filer-based filter — activity meta should only include activities the
                // selected supervisor actually filed a DPR for, matching the cells above.
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY d.activity_id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("referenceDate", referenceDate)
        .setParameter("monthStart", monthStart)
        .setParameter("monthEnd", monthEnd)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();

    Map<UUID, ActivityMeta> out = new LinkedHashMap<>(raw.size());
    for (Object[] r : raw) {
      UUID actId = (UUID) r[0];
      BigDecimal qtyDay = toBigDecimal(r[4]);
      BigDecimal qtyMonth = toBigDecimal(r[5]);
      BigDecimal subTotal = toBigDecimal(r[6]);
      out.put(actId, new ActivityMeta(actId, (String) r[1], (String) r[2],
          toBigDecimal(r[3]),
          qtyDay != null && qtyDay.signum() > 0 ? qtyDay : null,
          qtyMonth != null && qtyMonth.signum() > 0 ? qtyMonth : null,
          subTotal != null && subTotal.signum() > 0 ? subTotal : null,
          (String) r[7]));
    }
    return out;
  }

  /**
   * Resolve the display name for a supervisor User UUID. Phase 4.4 — the id is a User FK now,
   * so we look up {@code public.users} first and fall back to the latest DPR's snapshot name
   * for off-roster (legacy / free-text) supervisors.
   */
  private String resolveSupervisorName(UUID projectId, UUID supervisorUserId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT COALESCE(NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), u.username), "
                + "       u.username "
                + "FROM public.users u WHERE u.id = :id")
        .setParameter("id", supervisorUserId)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty()) {
      // Fall back to the latest DPR's snapshot name.
      @SuppressWarnings("unchecked")
      List<Object> snap = em.createNativeQuery(
              "SELECT supervisor_name FROM project.daily_progress_reports "
                  + "WHERE project_id = :p AND supervisor_user_id = :id "
                  + "ORDER BY report_date DESC LIMIT 1")
          .setParameter("p", projectId)
          .setParameter("id", supervisorUserId)
          .getResultList();
      return snap.isEmpty() ? null : (String) snap.get(0);
    }
    return (String) rows.get(0)[0];
  }

  // ─── Roll-up logic ──────────────────────────────────────────────────────────────────────────

  private List<TradeRollup> rollUpManpower(
      List<ManpowerCellRow> cells,
      Map<DprActivityKey, BigDecimal> equipmentExpected,
      Map<NormCacheKey, Budgeted> normCache,
      Map<UUID, String> normCombos,
      Map<UUID, BigDecimal> subContractorQtyByDpr,
      List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> hiddenOut,
      int workDays) {

    // Group cells by (DPR, activity) — one allocator call per group.
    Map<DprActivityKey, List<ManpowerCellRow>> groups = new LinkedHashMap<>();
    for (ManpowerCellRow c : cells) {
      if (c.dprId() == null || c.activityId() == null) continue;
      groups.computeIfAbsent(new DprActivityKey(c.dprId(), c.activityId()),
          k -> new ArrayList<>()).add(c);
    }

    Map<String, TradeAccumulator> byTrade = new LinkedHashMap<>();
    java.util.Set<UUID> notedActivities = new java.util.HashSet<>();

    for (var entry : groups.entrySet()) {
      DprActivityKey key = entry.getKey();
      List<ManpowerCellRow> groupCells = entry.getValue();

      // Build allocator inputs + this-side expected.
      List<CapacityAllocator.RoleInput> inputs = new ArrayList<>(groupCells.size());
      BigDecimal sideExpected = BigDecimal.ZERO;
      BigDecimal dprQty = BigDecimal.ZERO;
      for (ManpowerCellRow c : groupCells) {
        Budgeted norm = normCache.get(new NormCacheKey(
            c.workActivityId(), c.roleId(), c.resourceTypeId(), "MANPOWER"));
        BigDecimal n = norm == null ? null : norm.outputPerDay();
        int nos = c.actualNos() == null ? 0 : c.actualNos().intValue();
        inputs.add(new CapacityAllocator.RoleInput(c.roleId(), nos, n));
        if (n != null && n.signum() > 0 && nos > 0) {
          sideExpected = sideExpected.add(n.multiply(BigDecimal.valueOf(nos)));
        }
        if (c.qty() != null && c.qty().compareTo(dprQty) > 0) dprQty = c.qty();
      }
      // Effective qty for the allocator = DPR qty − sub-contractor qty, clamped to 0.
      BigDecimal subQty = subContractorQtyByDpr.getOrDefault(key.dprId(), BigDecimal.ZERO);
      BigDecimal qtyDone = dprQty.subtract(subQty);
      if (qtyDone.signum() < 0) qtyDone = BigDecimal.ZERO;
      BigDecimal otherExp = equipmentExpected.getOrDefault(key, BigDecimal.ZERO);
      String combo = normCombos.getOrDefault(key.activityId(), "SERIES");

      CapacityAllocator.AllocationResult result = CapacityAllocator.allocate(
          sideExpected, otherExp, qtyDone, combo, inputs);
      Map<UUID, CapacityAllocator.RoleAlloc> allocByRole = new HashMap<>();
      for (var ra : result.roleAllocations()) allocByRole.put(ra.roleId(), ra);

      if (result.hidden() && !notedActivities.contains(key.activityId())) {
        notedActivities.add(key.activityId());
        hiddenOut.add(new com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote(
            key.activityId(),
            groupCells.get(0).activityName(),
            "EQUIPMENT", // manpower side suppressed → equipment governs
            combo == null ? "SERIES" : combo.toUpperCase()));
      }

      // Credit each cell into its trade accumulator.
      for (ManpowerCellRow c : groupCells) {
        TradeAccumulator acc = byTrade.computeIfAbsent(c.tradeKey(),
            k -> new TradeAccumulator(c.tradeKey(), c.tradeLabel()));
        Budgeted norm = normCache.get(new NormCacheKey(
            c.workActivityId(), c.roleId(), c.resourceTypeId(), "MANPOWER"));
        BigDecimal cellActualDays = c.actualNos() == null ? BigDecimal.ZERO : c.actualNos();
        acc.actualDays = acc.actualDays.add(cellActualDays);
        if (c.lineCostTotal() != null) acc.lineCostTotal = acc.lineCostTotal.add(c.lineCostTotal());

        CapacityAllocator.RoleAlloc ra = allocByRole.get(c.roleId());
        if (result.hidden()) {
          if (ra != null && ra.normResolved()) {
            acc.actualDaysOnHiddenSides = acc.actualDaysOnHiddenSides.add(cellActualDays);
          } else {
            acc.actualDaysUntracked = acc.actualDaysUntracked.add(cellActualDays);
          }
        } else if (ra != null && ra.allocatedQty() != null) {
          // Tracked: credit allocated qty + budget = qty ÷ norm.
          BigDecimal alloc = ra.allocatedQty();
          acc.qtyDone = (acc.qtyDone == null) ? alloc : acc.qtyDone.add(alloc);
          if (norm != null && norm.outputPerDay() != null && norm.outputPerDay().signum() > 0) {
            BigDecimal cellBudget = alloc.divide(norm.outputPerDay(), 6, RoundingMode.HALF_UP);
            acc.budgetedDays = (acc.budgetedDays == null)
                ? cellBudget : acc.budgetedDays.add(cellBudget);
          }
        } else {
          // Visible side but role has no norm.
          acc.actualDaysUntracked = acc.actualDaysUntracked.add(cellActualDays);
        }

        if (norm != null && norm.source() != null && !"NONE".equals(norm.source())) {
          if (acc.normSource == null
              || normSourceRank(norm.source()) < normSourceRank(acc.normSource)) {
            acc.normSource = norm.source();
          }
        }
      }
    }

    List<TradeRollup> out = new ArrayList<>(byTrade.size());
    for (TradeAccumulator a : byTrade.values()) {
      out.add(buildTradeRollup(a, workDays));
    }
    out.sort(Comparator.comparing(TradeRollup::tradeLabel, Comparator.nullsLast(String::compareToIgnoreCase)));
    return out;
  }

  private List<EquipmentRollup> rollUpEquipment(
      List<EquipmentCellRow> cells,
      Map<DprActivityKey, BigDecimal> manpowerExpected,
      Map<NormCacheKey, Budgeted> normCache,
      Map<UUID, String> normCombos,
      Map<UUID, BigDecimal> subContractorQtyByDpr,
      List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> hiddenOut,
      int workDays) {

    Map<DprActivityKey, List<EquipmentCellRow>> groups = new LinkedHashMap<>();
    for (EquipmentCellRow c : cells) {
      if (c.dprId() == null || c.activityId() == null) continue;
      groups.computeIfAbsent(new DprActivityKey(c.dprId(), c.activityId()),
          k -> new ArrayList<>()).add(c);
    }

    Map<String, TradeAccumulator> byEquipment = new LinkedHashMap<>();
    java.util.Set<UUID> notedActivities = new java.util.HashSet<>();

    for (var entry : groups.entrySet()) {
      DprActivityKey key = entry.getKey();
      List<EquipmentCellRow> groupCells = entry.getValue();

      List<CapacityAllocator.RoleInput> inputs = new ArrayList<>(groupCells.size());
      BigDecimal sideExpected = BigDecimal.ZERO;
      BigDecimal dprQty = BigDecimal.ZERO;
      for (EquipmentCellRow c : groupCells) {
        Budgeted norm = normCache.get(new NormCacheKey(
            c.workActivityId(), c.roleId(), c.resourceTypeId(), "EQUIPMENT"));
        BigDecimal n = norm == null ? null : norm.outputPerDay();
        int nos = c.actualNos() == null ? 0 : c.actualNos().intValue();
        inputs.add(new CapacityAllocator.RoleInput(c.roleId(), nos, n));
        if (n != null && n.signum() > 0 && nos > 0) {
          sideExpected = sideExpected.add(n.multiply(BigDecimal.valueOf(nos)));
        }
        if (c.qty() != null && c.qty().compareTo(dprQty) > 0) dprQty = c.qty();
      }
      BigDecimal subQty = subContractorQtyByDpr.getOrDefault(key.dprId(), BigDecimal.ZERO);
      BigDecimal qtyDone = dprQty.subtract(subQty);
      if (qtyDone.signum() < 0) qtyDone = BigDecimal.ZERO;
      BigDecimal otherExp = manpowerExpected.getOrDefault(key, BigDecimal.ZERO);
      String combo = normCombos.getOrDefault(key.activityId(), "SERIES");

      CapacityAllocator.AllocationResult result = CapacityAllocator.allocate(
          sideExpected, otherExp, qtyDone, combo, inputs);
      Map<UUID, CapacityAllocator.RoleAlloc> allocByRole = new HashMap<>();
      for (var ra : result.roleAllocations()) allocByRole.put(ra.roleId(), ra);

      if (result.hidden() && !notedActivities.contains(key.activityId())) {
        notedActivities.add(key.activityId());
        hiddenOut.add(new com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote(
            key.activityId(),
            groupCells.get(0).activityName(),
            "MANPOWER",
            combo == null ? "SERIES" : combo.toUpperCase()));
      }

      for (EquipmentCellRow c : groupCells) {
        TradeAccumulator acc = byEquipment.computeIfAbsent(c.equipmentKey(),
            k -> new TradeAccumulator(c.equipmentKey(), c.equipmentLabel()));
        Budgeted norm = normCache.get(new NormCacheKey(
            c.workActivityId(), c.roleId(), c.resourceTypeId(), "EQUIPMENT"));
        BigDecimal cellActualDays = c.actualNos() == null ? BigDecimal.ZERO : c.actualNos();
        acc.actualDays = acc.actualDays.add(cellActualDays);
        if (c.lineCostTotal() != null) acc.lineCostTotal = acc.lineCostTotal.add(c.lineCostTotal());

        CapacityAllocator.RoleAlloc ra = allocByRole.get(c.roleId());
        if (result.hidden()) {
          if (ra != null && ra.normResolved()) {
            acc.actualDaysOnHiddenSides = acc.actualDaysOnHiddenSides.add(cellActualDays);
          } else {
            acc.actualDaysUntracked = acc.actualDaysUntracked.add(cellActualDays);
          }
        } else if (ra != null && ra.allocatedQty() != null) {
          BigDecimal alloc = ra.allocatedQty();
          acc.qtyDone = (acc.qtyDone == null) ? alloc : acc.qtyDone.add(alloc);
          if (norm != null && norm.outputPerDay() != null && norm.outputPerDay().signum() > 0) {
            BigDecimal cellBudget = alloc.divide(norm.outputPerDay(), 6, RoundingMode.HALF_UP);
            acc.budgetedDays = (acc.budgetedDays == null)
                ? cellBudget : acc.budgetedDays.add(cellBudget);
          }
        } else {
          acc.actualDaysUntracked = acc.actualDaysUntracked.add(cellActualDays);
        }

        if (norm != null && norm.source() != null && !"NONE".equals(norm.source())) {
          if (acc.normSource == null
              || normSourceRank(norm.source()) < normSourceRank(acc.normSource)) {
            acc.normSource = norm.source();
          }
        }
      }
    }

    List<EquipmentRollup> out = new ArrayList<>(byEquipment.size());
    for (TradeAccumulator a : byEquipment.values()) {
      out.add(buildEquipmentRollup(a, workDays));
    }
    out.sort(Comparator.comparing(EquipmentRollup::equipmentLabel, Comparator.nullsLast(String::compareToIgnoreCase)));
    return out;
  }

  private TradeRollup buildTradeRollup(TradeAccumulator a, int workDays) {
    BigDecimal mmRate = (a.actualDays.signum() > 0)
        ? a.lineCostTotal.divide(a.actualDays, 4, RoundingMode.HALF_UP)
        : null;
    // Util uses the TRACKED denominator only — suppressed + untracked days are excluded so
    // a role on a mix of governed and ungoverned activities isn't penalised.
    BigDecimal trackedActual = a.actualDays
        .subtract(a.actualDaysOnHiddenSides)
        .subtract(a.actualDaysUntracked);
    BigDecimal utilizationPct = computeUtilizationPct(a.budgetedDays, trackedActual);
    BigDecimal costImplication = (mmRate != null && a.budgetedDays != null)
        ? trackedActual.subtract(a.budgetedDays).multiply(mmRate).setScale(2, RoundingMode.HALF_UP)
        : null;
    BigDecimal hidden = a.actualDaysOnHiddenSides.signum() > 0
        ? a.actualDaysOnHiddenSides.setScale(2, RoundingMode.HALF_UP) : null;
    BigDecimal untracked = a.actualDaysUntracked.signum() > 0
        ? a.actualDaysUntracked.setScale(2, RoundingMode.HALF_UP) : null;
    return new TradeRollup(
        a.key, a.label,
        mmRate != null ? mmRate.setScale(2, RoundingMode.HALF_UP) : null,
        a.qtyDone != null ? a.qtyDone.setScale(2, RoundingMode.HALF_UP) : null,
        a.budgetedDays != null ? a.budgetedDays.setScale(2, RoundingMode.HALF_UP) : null,
        a.actualDays.setScale(2, RoundingMode.HALF_UP),
        hidden, untracked,
        utilizationPct,
        costImplication,
        a.normSource != null ? a.normSource : "NONE");
  }

  private EquipmentRollup buildEquipmentRollup(TradeAccumulator a, int workDays) {
    BigDecimal hourRate = (a.actualDays.signum() > 0)
        ? a.lineCostTotal.divide(a.actualDays, 4, RoundingMode.HALF_UP)
        : null;
    BigDecimal trackedActual = a.actualDays
        .subtract(a.actualDaysOnHiddenSides)
        .subtract(a.actualDaysUntracked);
    BigDecimal utilizationPct = computeUtilizationPct(a.budgetedDays, trackedActual);
    BigDecimal costImplication = (hourRate != null && a.budgetedDays != null)
        ? trackedActual.subtract(a.budgetedDays).multiply(hourRate).setScale(2, RoundingMode.HALF_UP)
        : null;
    BigDecimal hidden = a.actualDaysOnHiddenSides.signum() > 0
        ? a.actualDaysOnHiddenSides.setScale(2, RoundingMode.HALF_UP) : null;
    BigDecimal untracked = a.actualDaysUntracked.signum() > 0
        ? a.actualDaysUntracked.setScale(2, RoundingMode.HALF_UP) : null;
    return new EquipmentRollup(
        a.key, a.label,
        hourRate != null ? hourRate.setScale(2, RoundingMode.HALF_UP) : null,
        a.qtyDone != null ? a.qtyDone.setScale(2, RoundingMode.HALF_UP) : null,
        a.budgetedDays != null ? a.budgetedDays.setScale(2, RoundingMode.HALF_UP) : null,
        a.actualDays.setScale(2, RoundingMode.HALF_UP),
        hidden, untracked,
        utilizationPct,
        costImplication,
        a.normSource != null ? a.normSource : "NONE");
  }

  // ─── Activity drill-down ────────────────────────────────────────────────────────────────────

  private List<ActivityDrillDown> buildDrillDown(
      List<ManpowerCellRow> manpowerCells, List<EquipmentCellRow> equipmentCells,
      Map<NormCacheKey, Budgeted> normCache, Map<UUID, ActivityMeta> activityMeta,
      Map<ActivityRoleKey, BigDecimal> plannedByActivityRole,
      Map<DprActivityKey, BigDecimal> manpowerExpected,
      Map<DprActivityKey, BigDecimal> equipmentExpected,
      Map<UUID, String> normCombos,
      Map<UUID, BigDecimal> subContractorQtyByDpr,
      int workDays) {

    // Aggregate at (activity, kind, resourceKey) so two cells with the same trade key but
    // different resource_type_ids (e.g. role "ROLE-HELPER" attached to two different LABOR types)
    // don't produce two ResourceLines per activity. React would then see duplicate keys and
    // fall back to a slow reconciliation path that thrashes the page.
    //
    // Per-DPR allocation: each cell's qty becomes the allocator's per-role share for THAT DPR,
    // not the full DPR qty. Suppressed cells contribute zero qty (so the drill-down's per-row
    // budget / FTM goes blank with a "this side was suppressed" reading via the parent banner).
    Map<UUID, Map<String, ResourceLineAccumulator>> byActivity = new LinkedHashMap<>();
    Map<DprActivityKey, Map<UUID, CapacityAllocator.RoleAlloc>> mpAllocByGroup =
        computeAllocations(manpowerCells, equipmentExpected, normCache, normCombos,
            subContractorQtyByDpr, "MANPOWER");
    Map<DprActivityKey, Map<UUID, CapacityAllocator.RoleAlloc>> eqAllocByGroup =
        computeAllocations(equipmentCells, manpowerExpected, normCache, normCombos,
            subContractorQtyByDpr, "EQUIPMENT");

    for (ManpowerCellRow c : manpowerCells) {
      if (c.activityId() == null) continue;
      Budgeted norm = normCache.get(new NormCacheKey(c.workActivityId(), c.roleId(), c.resourceTypeId(), "MANPOWER"));
      BigDecimal allocQty = lookupAlloc(mpAllocByGroup, c.dprId(), c.activityId(), c.roleId());
      mergeIntoActivity(byActivity, c.activityId(), "MANPOWER",
          c.tradeKey(), c.tradeLabel(), allocQty, c.actualNos(), norm, c.roleId());
    }
    for (EquipmentCellRow c : equipmentCells) {
      if (c.activityId() == null) continue;
      Budgeted norm = normCache.get(new NormCacheKey(c.workActivityId(), c.roleId(), c.resourceTypeId(), "EQUIPMENT"));
      BigDecimal allocQty = lookupAlloc(eqAllocByGroup, c.dprId(), c.activityId(), c.roleId());
      mergeIntoActivity(byActivity, c.activityId(), "EQUIPMENT",
          c.equipmentKey(), c.equipmentLabel(), allocQty, c.actualNos(), norm, c.roleId());
    }

    Map<UUID, ActivityHeader> headers = new HashMap<>();
    for (ManpowerCellRow c : manpowerCells) {
      headers.putIfAbsent(c.activityId(),
          new ActivityHeader(c.activityCode(), c.activityName(), c.activityUnit()));
    }
    for (EquipmentCellRow c : equipmentCells) {
      headers.putIfAbsent(c.activityId(),
          new ActivityHeader(c.activityCode(), c.activityName(), c.activityUnit()));
    }

    List<ActivityDrillDown> out = new ArrayList<>(byActivity.size());
    for (Map.Entry<UUID, Map<String, ResourceLineAccumulator>> e : byActivity.entrySet()) {
      UUID actId = e.getKey();
      ActivityMeta meta = activityMeta.get(actId);
      ActivityHeader header = headers.get(actId);
      List<ResourceLine> lines = new ArrayList<>(e.getValue().size());
      for (ResourceLineAccumulator acc : e.getValue().values()) {
        BigDecimal plannedHeadcount = acc.roleId == null ? null
            : plannedByActivityRole.get(new ActivityRoleKey(actId, acc.roleId));
        lines.add(buildResourceLine(acc.kind, acc.resourceKey, acc.resourceLabel,
            acc.qty, acc.actualNos, acc.norm, plannedHeadcount, workDays));
      }
      out.add(new ActivityDrillDown(
          actId,
          header != null ? header.code() : null,
          header != null ? header.name() : (meta != null ? meta.activityName() : null),
          header != null ? header.unit() : (meta != null ? meta.unit() : null),
          meta != null ? meta.qtyTotal() : null,
          meta != null ? meta.subContractorQty() : null,
          lines,
          meta != null ? meta.remarks() : null));
    }
    out.sort(Comparator.comparing(
        a -> a.activityCode() != null ? a.activityCode() : "",
        Comparator.nullsLast(String::compareTo)));
    return out;
  }

  private static void mergeIntoActivity(
      Map<UUID, Map<String, ResourceLineAccumulator>> byActivity,
      UUID activityId, String kind, String resourceKey, String resourceLabel,
      BigDecimal qty, BigDecimal actualNos, Budgeted norm, UUID roleId) {
    Map<String, ResourceLineAccumulator> linesByKey =
        byActivity.computeIfAbsent(activityId, k -> new LinkedHashMap<>());
    String dedupeKey = kind + "::" + resourceKey;
    ResourceLineAccumulator acc = linesByKey.get(dedupeKey);
    if (acc == null) {
      // qty is null for suppressed / untracked cells; leave null so buildResourceLine renders a
      // blank Qty / Budget / FTM column (an activity-level banner explains why).
      linesByKey.put(dedupeKey, new ResourceLineAccumulator(kind, resourceKey, resourceLabel,
          qty,
          actualNos == null ? BigDecimal.ZERO : actualNos,
          norm, roleId));
    } else {
      if (qty != null) acc.qty = (acc.qty == null) ? qty : acc.qty.add(qty);
      if (actualNos != null) acc.actualNos = acc.actualNos.add(actualNos);
      // Preserve the first non-NONE norm encountered. Different resource_type_ids inside the
      // same trade typically share the same type-level norm, so this is harmless; if they
      // genuinely differ, the first-wins choice is documented as a known limitation.
      if ((acc.norm == null || acc.norm.source() == null || "NONE".equals(acc.norm.source()))
          && norm != null) {
        acc.norm = norm;
      }
    }
  }

  private static final class ResourceLineAccumulator {
    final String kind;
    final String resourceKey;
    final String resourceLabel;
    final UUID roleId;
    /** Nullable. Null means this resource line had no tracked DPR contributions (every cell was
     *  suppressed or untracked). buildResourceLine renders Qty / Budget / FTM as blank. */
    BigDecimal qty;
    /** Raw sum of nos (headcount-days) across DPRs — hours are not multiplied in. */
    BigDecimal actualNos;
    Budgeted norm;
    ResourceLineAccumulator(String kind, String resourceKey, String resourceLabel,
                            BigDecimal qty, BigDecimal actualNos, Budgeted norm, UUID roleId) {
      this.kind = kind;
      this.resourceKey = resourceKey;
      this.resourceLabel = resourceLabel;
      this.qty = qty;
      this.actualNos = actualNos;
      this.norm = norm;
      this.roleId = roleId;
    }
  }

  private ResourceLine buildResourceLine(
      String kind, String key, String label,
      BigDecimal qty, BigDecimal actualNos, Budgeted norm,
      BigDecimal plannedHeadcount, int workDays) {

    // actualDays is now the raw sum of nos — DAY-basis semantics, hours are not multiplied in.
    BigDecimal actualDays = actualNos != null ? actualNos : BigDecimal.ZERO;
    BigDecimal budgetDays = (norm != null && norm.outputPerDay() != null
        && norm.outputPerDay().signum() > 0 && qty != null)
        ? qty.divide(norm.outputPerDay(), 6, RoundingMode.HALF_UP)
        : null;
    // Actual FTM (qty ÷ actualDays) is the realised productivity rate per resource-day, meant
    // to be compared against the norm's Budget rate. When there's no Budget (role has no
    // productivity norm), the FTM number is mathematically true but meaningless — it'd read as
    // "1 carpenter-day produced 300 m" when really the carpenter was just present alongside the
    // mason and excavator who drove the output. Suppress it so the column reads cleanly: norms
    // present → both Budget + FTM shown; no norm → both blank.
    boolean budgetExists = budgetDays != null;
    BigDecimal actualsFtm = (budgetExists && qty != null && actualDays.signum() > 0)
        ? qty.divide(actualDays, 4, RoundingMode.HALF_UP)
        : null;
    BigDecimal utilizationPct = computeUtilizationPct(budgetDays, actualDays);

    PlannedActuals actuals = new PlannedActuals(
        qty != null ? qty.setScale(2, RoundingMode.HALF_UP) : null,
        budgetDays != null ? budgetDays.setScale(2, RoundingMode.HALF_UP) : null,
        actualDays.setScale(2, RoundingMode.HALF_UP),
        utilizationPct);

    // Plan column carries the RAW planned headcount (= nos) from RoleAssignment.plannedUnits.
    // We intentionally don't multiply by workDays — comparing "planned nos" against "actual
    // days" mixes units and produces meaningless utilization figures (the 6,500% we saw
    // earlier). The frontend labels this field as "X nos" so the user reads it directly.
    // The plan-side %Util is suppressed for the same reason; budget-vs-actual %Util is the
    // only comparable utilization metric and lives in the Actuals column.
    BigDecimal plannedNos = (plannedHeadcount != null && plannedHeadcount.signum() > 0)
        ? plannedHeadcount
        : null;
    PlannedActuals plan = new PlannedActuals(
        qty != null ? qty.setScale(2, RoundingMode.HALF_UP) : null,
        budgetDays != null ? budgetDays.setScale(2, RoundingMode.HALF_UP) : null,
        plannedNos != null ? plannedNos.setScale(2, RoundingMode.HALF_UP) : null,
        null);

    ProductivityNorms norms = new ProductivityNorms(
        norm != null ? norm.outputPerDay() : null,
        null,
        actualsFtm != null ? actualsFtm.setScale(2, RoundingMode.HALF_UP) : null,
        norm != null ? norm.source() : "NONE");

    return new ResourceLine(kind, key, label, norms, plan, actuals);
  }

  /**
   * Pre-fetch {@code RoleAssignment.plannedUnits} for every (activity, role) pair that appears
   * in the manpower or equipment cells. Used by the drill-down's PLAN column.
   */
  @SuppressWarnings("unchecked")
  private Map<ActivityRoleKey, BigDecimal> loadPlannedUnitsByActivityRole(
      UUID projectId, List<ManpowerCellRow> manpowerCells, List<EquipmentCellRow> equipmentCells) {
    java.util.Set<UUID> roleIds = new java.util.HashSet<>();
    java.util.Set<UUID> activityIds = new java.util.HashSet<>();
    for (ManpowerCellRow c : manpowerCells) {
      if (c.roleId() != null) roleIds.add(c.roleId());
      if (c.activityId() != null) activityIds.add(c.activityId());
    }
    for (EquipmentCellRow c : equipmentCells) {
      if (c.roleId() != null) roleIds.add(c.roleId());
      if (c.activityId() != null) activityIds.add(c.activityId());
    }
    if (roleIds.isEmpty() || activityIds.isEmpty()) return Map.of();
    // Display the raw nos the planner entered, not the headcount × duration product the legacy
    // planned_units column stores. Falls through to quantity for material rows and to the legacy
    // planned_units only for pre-headcount rows. Same COALESCE pattern as the capacity-util
    // bucketed planned loader (CapacityUtilizationReportService.loadPlannedHeadcountByBucket).
    List<Object[]> rows = em.createNativeQuery(
            "SELECT ra.activity_id, ra.role_id, "
                + "       COALESCE(ra.headcount, ra.quantity, ra.planned_units) AS planned_nos "
                + "FROM resource.resource_assignments ra "
                + "JOIN activity.activities a ON a.id = ra.activity_id "
                + "WHERE a.project_id = :projectId "
                + "  AND ra.activity_id IN :activityIds "
                + "  AND ra.role_id IN :roleIds")
        .setParameter("projectId", projectId)
        .setParameter("activityIds", activityIds)
        .setParameter("roleIds", roleIds)
        .getResultList();
    Map<ActivityRoleKey, BigDecimal> out = new HashMap<>();
    for (Object[] r : rows) {
      UUID actId = (UUID) r[0];
      UUID roleId = (UUID) r[1];
      BigDecimal units = toBigDecimal(r[2]);
      if (actId == null || roleId == null || units == null || units.signum() <= 0) continue;
      // Sum in case multiple role-assignments target the same (activity, role) pair.
      out.merge(new ActivityRoleKey(actId, roleId), units, BigDecimal::add);
    }
    return out;
  }

  // ─── Math helpers (package-private for tests) ──────────────────────────────────────────────

  static BigDecimal computeUtilizationPct(BigDecimal budgetedDays, BigDecimal actualDays) {
    if (budgetedDays == null || actualDays == null || actualDays.signum() <= 0) {
      return null;
    }
    BigDecimal pct = budgetedDays.divide(actualDays, 8, RoundingMode.HALF_UP).multiply(HUNDRED);
    return pct.min(UTILIZATION_CAP).setScale(2, RoundingMode.HALF_UP);
  }

  static BigDecimal computeCostImplication(
      BigDecimal actualDays, BigDecimal budgetedDays, BigDecimal rate) {
    if (rate == null || budgetedDays == null || actualDays == null) return null;
    return actualDays.subtract(budgetedDays).multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return null;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }

  private static LocalDate toLocalDate(Object o) {
    if (o == null) return null;
    if (o instanceof LocalDate ld) return ld;
    if (o instanceof java.sql.Date sd) return sd.toLocalDate();
    if (o instanceof java.util.Date ud) return ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    return null;
  }

  private static int normSourceRank(String source) {
    if (source == null) return 99;
    return switch (source) {
      case "SPECIFIC_RESOURCE" -> 0;
      case "RESOURCE_TYPE" -> 1;
      case "RESOURCE_LEGACY" -> 2;
      default -> 99;
    };
  }

  // ─── Internal record types ──────────────────────────────────────────────────────────────────

  private record ManpowerCellRow(
      String tradeKey, String tradeLabel, UUID resourceTypeId,
      UUID activityId, UUID workActivityId,
      String activityCode, String activityName, String activityUnit,
      /** DPR's qty_executed (one DPR per cell now since dpr_id is in GROUP BY). */
      BigDecimal qty,
      /** Raw sum of headcount across DPR rows that share (DPR, role) — hours NOT multiplied in. */
      BigDecimal actualNos,
      BigDecimal lineCostTotal,
      /** Role FK used by the drill-down to look up RoleAssignment.plannedUnits. */
      UUID roleId,
      /** DPR id — needed by the allocator's per-DPR grouping. */
      UUID dprId,
      /** DPR's report_date — used by the 3-pass bucket stitcher (Day / CalendarMonth /
       *  Cumulative). All cells of a given DPR share the same date by construction. */
      LocalDate reportDate) {}

  private record EquipmentCellRow(
      String equipmentKey, String equipmentLabel, UUID resourceTypeId,
      UUID activityId, UUID workActivityId,
      String activityCode, String activityName, String activityUnit,
      BigDecimal qty,
      /** Raw sum of equipment headcount across DPR rows that share (DPR, role) — hours NOT multiplied in. */
      BigDecimal actualNos,
      BigDecimal lineCostTotal,
      UUID roleId,
      UUID dprId,
      /** DPR's report_date — see {@link ManpowerCellRow#reportDate}. */
      LocalDate reportDate) {}

  /** (activityId, roleId) → plannedUnits — drill-down uses this for the real Plan column. */
  private record ActivityRoleKey(UUID activityId, UUID roleId) {}

  /** (dprId, activityId) — group key for per-DPR allocation. */
  private record DprActivityKey(UUID dprId, UUID activityId) {}

  /**
   * Build a per-(DPR, activity) → per-role allocation map for the drill-down to consume. Same
   * grouping + allocator call as {@link #rollUpManpower} / {@link #rollUpEquipment}, but
   * returns the raw allocations instead of accumulating into trade rollups. Cells parameterised
   * as {@code List<?>} since both ManpowerCellRow and EquipmentCellRow are processed identically.
   */
  private Map<DprActivityKey, Map<UUID, CapacityAllocator.RoleAlloc>> computeAllocations(
      List<?> cells, Map<DprActivityKey, BigDecimal> otherSideExpected,
      Map<NormCacheKey, Budgeted> normCache, Map<UUID, String> normCombos,
      Map<UUID, BigDecimal> subContractorQtyByDpr,
      String thisSideNormType) {

    Map<DprActivityKey, List<Object>> groups = new LinkedHashMap<>();
    for (Object o : cells) {
      UUID dprId, activityId;
      if (o instanceof ManpowerCellRow m) { dprId = m.dprId(); activityId = m.activityId(); }
      else if (o instanceof EquipmentCellRow e) { dprId = e.dprId(); activityId = e.activityId(); }
      else continue;
      if (dprId == null || activityId == null) continue;
      groups.computeIfAbsent(new DprActivityKey(dprId, activityId), k -> new ArrayList<>()).add(o);
    }

    Map<DprActivityKey, Map<UUID, CapacityAllocator.RoleAlloc>> out = new HashMap<>();
    for (var entry : groups.entrySet()) {
      DprActivityKey key = entry.getKey();
      List<CapacityAllocator.RoleInput> inputs = new ArrayList<>();
      BigDecimal sideExpected = BigDecimal.ZERO;
      BigDecimal dprQty = BigDecimal.ZERO;
      for (Object o : entry.getValue()) {
        UUID workActivityId, roleId, resourceTypeId;
        BigDecimal actualNos, q;
        if (o instanceof ManpowerCellRow m) {
          workActivityId = m.workActivityId(); roleId = m.roleId();
          resourceTypeId = m.resourceTypeId(); actualNos = m.actualNos(); q = m.qty();
        } else {
          EquipmentCellRow e = (EquipmentCellRow) o;
          workActivityId = e.workActivityId(); roleId = e.roleId();
          resourceTypeId = e.resourceTypeId(); actualNos = e.actualNos(); q = e.qty();
        }
        Budgeted norm = normCache.get(new NormCacheKey(workActivityId, roleId, resourceTypeId, thisSideNormType));
        BigDecimal n = norm == null ? null : norm.outputPerDay();
        int nos = actualNos == null ? 0 : actualNos.intValue();
        inputs.add(new CapacityAllocator.RoleInput(roleId, nos, n));
        if (n != null && n.signum() > 0 && nos > 0) {
          sideExpected = sideExpected.add(n.multiply(BigDecimal.valueOf(nos)));
        }
        if (q != null && q.compareTo(dprQty) > 0) dprQty = q;
      }
      BigDecimal subQty = subContractorQtyByDpr.getOrDefault(key.dprId(), BigDecimal.ZERO);
      BigDecimal qtyDone = dprQty.subtract(subQty);
      if (qtyDone.signum() < 0) qtyDone = BigDecimal.ZERO;
      BigDecimal otherExp = otherSideExpected.getOrDefault(key, BigDecimal.ZERO);
      String combo = normCombos.getOrDefault(key.activityId(), "SERIES");
      CapacityAllocator.AllocationResult result = CapacityAllocator.allocate(
          sideExpected, otherExp, qtyDone, combo, inputs);
      Map<UUID, CapacityAllocator.RoleAlloc> byRole = new HashMap<>();
      for (var ra : result.roleAllocations()) byRole.put(ra.roleId(), ra);
      out.put(key, byRole);
    }
    return out;
  }

  /** Allocated qty for a (DPR, activity, role) cell. Null if hidden or untracked — caller
   *  should treat null as "this cell contributes zero qty to the drill-down". */
  private static BigDecimal lookupAlloc(
      Map<DprActivityKey, Map<UUID, CapacityAllocator.RoleAlloc>> allocByGroup,
      UUID dprId, UUID activityId, UUID roleId) {
    if (dprId == null || activityId == null || roleId == null) return null;
    Map<UUID, CapacityAllocator.RoleAlloc> byRole = allocByGroup.get(new DprActivityKey(dprId, activityId));
    if (byRole == null) return null;
    CapacityAllocator.RoleAlloc ra = byRole.get(roleId);
    return ra == null ? null : ra.allocatedQty();
  }

  private record ActivityMeta(
      UUID activityId, String activityName, String unit,
      BigDecimal qtyTotal, BigDecimal qtyForDay, BigDecimal qtyForCalendarMonth,
      BigDecimal subContractorQty, String remarks) {}

  private record ActivityHeader(String code, String name, String unit) {}

  /** Norm cache key. {@code roleId} included so MASON and HELPER (both LABOR-type) don't share a
   *  cached norm — the resolver picks a role-keyed norm when present, type-keyed otherwise. */
  private record NormCacheKey(UUID workActivityId, UUID roleId, UUID resourceTypeId, String normType) {}

  private static final class TradeAccumulator {
    final String key;
    final String label;
    /** Total deployment days (raw Σ nos) across all DPR contributions — tracked + suppressed +
     *  untracked. Wage-payable basis. */
    BigDecimal actualDays = BigDecimal.ZERO;
    /** Deployment days on activities where THIS side was suppressed by the allocator
     *  (SERIES/SUBSTITUTE governed by other side, role had a resolvable norm). Subset of
     *  {@link #actualDays}. Excluded from the Efficiency denominator. */
    BigDecimal actualDaysOnHiddenSides = BigDecimal.ZERO;
    /** Deployment days on activities where this role had NO resolvable norm (productivity
     *  not measured). Subset of {@link #actualDays}. Excluded from the Efficiency denominator. */
    BigDecimal actualDaysUntracked = BigDecimal.ZERO;
    BigDecimal budgetedDays = null;     // null = no tracked cells contributed a budget
    /** ALLOCATED qty for this trade — sum of CapacityAllocator.RoleAlloc.allocatedQty across
     *  tracked (DPR, activity) groups. Null when no tracked cells. */
    BigDecimal qtyDone = null;
    BigDecimal lineCostTotal = BigDecimal.ZERO;
    String normSource = null;

    TradeAccumulator(String key, String label) {
      this.key = key;
      this.label = label;
    }
  }
}
