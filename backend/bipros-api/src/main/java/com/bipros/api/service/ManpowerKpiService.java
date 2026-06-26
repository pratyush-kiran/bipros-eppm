package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.enums.SalaryType;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.manpower.ManpowerAttendance;
import com.bipros.resource.domain.model.manpower.ManpowerFinancials;
import com.bipros.resource.domain.repository.ManpowerAttendanceRepository;
import com.bipros.resource.domain.repository.ManpowerFinancialsRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregator KPI service for manpower metrics. Reads exclusively from DPR + child rows
 * (daily_progress_reports, dpr_manpower) — Daily Outputs (DAR) is being deprecated and is no
 * longer consulted here. The framework's "qty per activity per day" comes from DPR.qty_executed
 * and "man-hours" comes from dpr_manpower (Σ nos × working_hours).
 *
 * <p>Cost normalisation prefers {@code dpr_manpower.line_cost} when set (the supervisor entered
 * it directly during DPR save) and falls back to {@code nos × working_hours × hourlyRate} from
 * {@code manpower_financials} otherwise.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManpowerKpiService {

  // ResourceTypeSeeder writes "MANPOWER" (renamed from the original "LABOR"); the
  // KPI used to look for "LABOR" so it found zero labour resources and reported
  // 0.0% Workforce Utilisation regardless of DPR content.
  private static final String LABOR_TYPE_CODE = "MANPOWER";
  private static final double DEFAULT_HOURS_PER_DAY = 8d;
  /** Indian Factories Act §59 — minimum 2× base rate for overtime. */
  private static final double OT_MULTIPLIER = 2.0d;

  private final BoqItemRepository boqItemRepository;
  private final ActivityRepository activityRepository;
  private final ResourceRepository resourceRepository;
  private final ManpowerAttendanceRepository attendanceRepository;
  private final ManpowerFinancialsRepository financialsRepository;
  private final ProductivityNormRepository productivityNormRepository;
  private final ResourceAssignmentRepository resourceAssignmentRepository;
  private final DailyProgressReportRepository dprRepository;
  private final DprManpowerRepository dprManpowerRepository;
  private final DprSubContractorRepository dprSubContractorRepository;

  // ---------- Response records (shape unchanged so frontend keeps rendering) ----------

  public record ManpowerKpiResponse(
      UUID projectId,
      LocalDate from,
      LocalDate to,
      WorkforceUtilization workforceUtilization,
      List<ProductivityFactorRow> productivityFactor,
      double headlineProductivityFactor,
      List<LabourCostPerUnitRow> labourCostPerUnit,
      double weightedAvgCostPerUnit,
      List<CrewOutputRow> crewOutput,
      double idleTimeRatioPct,
      double overtimeRatioPct,
      List<OutputAchievementRow> outputAchievement,
      LabourCostSummary labourCostSummary,
      double cumulativeProgressPct,
      DataQuality dataQuality
  ) {}

  /**
   * KPI 3.1–3.4 + 3.7 cost block. PLC = Σ over LABOR resource_assignments of
   * (planned_units × hourly_rate × overlap_with_window/duration). ALC = Σ DPR labour cost
   * (line_cost when set, fallback nos × (regular_hrs + ot_hrs × 2.0) × hourly_rate). LCV
   * positive = under budget. LCPI ≥ 1.0 = on budget. OT Cost % = OT premium pay / total wage bill.
   */
  public record LabourCostSummary(
      double plannedLabourCost,
      double actualLabourCost,
      double labourCostVariance,
      double lcpi,
      double otCostPct,
      int activityCoverageCount,
      int missingPlanCount
  ) {}

  public record WorkforceUtilization(
      int actualNos,
      int plannedNos,
      double utilizationPct,
      double rawUtilizationPct,
      boolean overflow,
      int laborResourceCount,
      int activeResourceCount,
      int missingAttendanceCount
  ) {}

  public record ProductivityFactorRow(
      UUID activityId,
      String activityName,
      String darUnit,
      String normUnit,
      double actualOutputPerManPerDay,
      double normOutputPerManPerDay,
      double factor,
      boolean unitMismatch
  ) {}

  public record LabourCostPerUnitRow(
      UUID boqItemId,
      String itemNo,
      String description,
      String unit,
      double labourCost,
      double qtyExecuted,
      double costPerUnit
  ) {}

  public record CrewOutputRow(
      UUID activityId,
      String activityName,
      Integer crewSize,
      double actualOutputPerDay,
      double normOutputPerDay,
      double deviationPct
  ) {}

  public record OutputAchievementRow(
      UUID activityId,
      String activityName,
      double actualDailyOutput,
      double plannedDailyOutput,
      double achievementPct
  ) {}

  public record DataQuality(
      int missingRateResourceCount,
      List<String> missingRateResourceCodes,
      int missingAttendanceResourceCount,
      List<String> missingAttendanceResourceCodes,
      int unitMismatchActivityCount,
      int noNormActivityCount,
      int noBoqBaselineActivityCount
  ) {}

  // ---------- Internal aggregation helper ----------

  /**
   * Per-DPR manpower roll-up: total man-hours, OT hours, idle hours, labour cost.
   */
  private record DprManpowerAgg(double manHours, double otHours, double idleHours, double cost) {}

  /**
   * Per-activity aggregation across all DPRs in the window.
   */
  private record ActivityAgg(
      String activityName,
      String dprUnit,
      double qtyExecuted,
      double manHours,
      int totalNos,
      Map<UUID, Integer> nosByRole,
      Set<LocalDate> daysSeen) {}

  // ---------- Public API ----------

  @Transactional(readOnly = true)
  public ManpowerKpiResponse compute(UUID projectId, LocalDate from, LocalDate to) {
    List<DailyProgressReport> dprs = dprRepository
        .findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, DprApprovalStatus.APPROVED, from, to);

    if (dprs.isEmpty()) {
      return emptyResponse(projectId, from, to);
    }

    Set<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).collect(Collectors.toSet());
    List<DprManpower> manpowerRows = dprManpowerRepository.findByDprIdIn(dprIds);
    Map<UUID, List<DprManpower>> manpowerByDpr = manpowerRows.stream()
        .collect(Collectors.groupingBy(DprManpower::getDprId));

    // Resolve resources for the legacy chain (financials / attendance lookup keyed on resource_id).
    // For role-only DPR rows (resource_id = null, role_id + manpower_role_rate_id set) these
    // maps stay empty and the cost path falls through to line_cost (populated at DPR save time
    // from manpower_role_rates with project-override resolution).
    Set<UUID> resourceIds = manpowerRows.stream()
        .map(DprManpower::getResourceId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    Map<UUID, Resource> resourcesById = resourceRepository.findAllById(resourceIds).stream()
        .collect(Collectors.toMap(Resource::getId, r -> r, (a, b) -> a));
    Set<UUID> labourResourceIds = resourcesById.values().stream()
        .filter(r -> r.getResourceType() != null && LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode()))
        .map(Resource::getId)
        .collect(Collectors.toSet());

    Map<UUID, ManpowerAttendance> attendanceById = attendanceRepository.findAllById(labourResourceIds).stream()
        .collect(Collectors.toMap(ManpowerAttendance::getResourceId, a -> a, (a, b) -> a));
    Map<UUID, ManpowerFinancials> financialsById = financialsRepository.findAllById(resourceIds).stream()
        .collect(Collectors.toMap(ManpowerFinancials::getResourceId, f -> f, (a, b) -> a));

    // Role-only identity set: union of (resource_id) and (role_id) across deployed rows. Used
    // for the headline "X of Y labour active" counter and as the synthetic resource key when
    // resource_id is null. Treat every dpr_manpower row as labour by definition (the table is
    // for manpower deployment).
    Set<UUID> deployedIdentities = manpowerRows.stream()
        .map(m -> m.getResourceId() != null ? m.getResourceId() : m.getRoleId())
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());

    // Pre-compute per-DPR roll-up so each downstream KPI can pull from one place.
    Map<UUID, DprManpowerAgg> aggByDpr = computeAggByDpr(manpowerByDpr, financialsById);

    // Per-activity aggregation: qty from DPR parent, man-hours from rolled-up children.
    Map<String, ActivityAgg> aggByActivity = computeAggByActivity(dprs, aggByDpr, manpowerByDpr);
    Map<String, UUID> activityIdsByKey = aggByActivity.keySet().stream()
        .filter(k -> k.startsWith("id:"))
        .collect(Collectors.toMap(k -> k, k -> UUID.fromString(k.substring(3))));
    Map<UUID, Activity> activitiesById = activityRepository
        .findAllById(activityIdsByKey.values()).stream()
        .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

    // BOQ list — shared by Labour Cost / Unit and Output Achievement % (planned-daily baseline).
    List<BoqItem> boqItems = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    Map<UUID, BoqItem> boqById = boqItems.stream()
        .collect(Collectors.toMap(BoqItem::getId, b -> b, (a, b) -> a));

    // Pre-resolve per-activity BOQ: prefer the first DPR's hard FK; fall back to fuzzy name.
    // Activities pointing to multiple BOQs (rare) take the most common boqItemId.
    Map<String, BoqItem> boqByActivityKey = resolveBoqByActivity(dprs, boqItems, boqById);

    // Fetch assignments once; both workforce util and labour cost summary use them.
    List<ResourceAssignment> projectAssignments = resourceAssignmentRepository.findByProjectId(projectId);

    // Sub-contractor quantity per activity, used to subtract from qty_executed so PF + CPU
    // reflect supervisor's crew productivity only, not crew + SC combined.
    java.util.Map<UUID, BigDecimal> scQtyByActivity = new java.util.HashMap<>();
    for (Object[] row : dprSubContractorRepository.sumQuantityByProjectGroupedByActivityApproved(projectId)) {
      if (row[0] != null) scQtyByActivity.put((UUID) row[0], (BigDecimal) row[1]);
    }

    java.util.Map<UUID, BigDecimal> scQtyByBoqItem = new java.util.HashMap<>();
    for (Object[] row : dprSubContractorRepository.sumQuantityByProjectGroupedByBoqItemApproved(projectId)) {
      if (row[0] != null) scQtyByBoqItem.put((UUID) row[0], (BigDecimal) row[1]);
    }

    // KPI computations
    WorkforceUtilization workforce = computeWorkforceUtilization(
        manpowerRows, projectAssignments, resourcesById, deployedIdentities);
    List<ProductivityFactorRow> productivity =
        computeProductivityFactor(aggByActivity, activitiesById, scQtyByActivity);
    double headlineFactor = computeHeadlineProductivityFactor(productivity);
    List<LabourCostPerUnitRow> labourCost =
        computeLabourCostPerUnit(boqItems, dprs, aggByDpr, scQtyByBoqItem);
    double weightedCpu = computeWeightedAvgCostPerUnit(labourCost);
    List<CrewOutputRow> crews = computeCrewOutput(aggByActivity, activitiesById);
    double idleRatio = computeIdleTimeRatioPct(aggByDpr);
    double otRatio = computeOvertimeRatioPct(aggByDpr);

    int[] noBoqBaselineCount = new int[1];
    List<OutputAchievementRow> achievement =
        computeOutputAchievement(aggByActivity, activitiesById, boqByActivityKey, noBoqBaselineCount);

    int noNormCount = (int) productivity.stream()
        .filter(r -> r.normOutputPerManPerDay() <= 0d)
        .count();

    LabourCostSummary costSummary = computeLabourCostSummary(
        projectId, from, to, resourcesById, financialsById, aggByDpr, manpowerByDpr);

    double cumulativePct = computeCumulativeProgressPct(
        projectId, from, to, dprs, activitiesById, boqItems);

    DataQuality dq = buildDataQuality(
        labourResourceIds, resourcesById, attendanceById, financialsById,
        productivity, noNormCount, noBoqBaselineCount[0]);

    return new ManpowerKpiResponse(
        projectId,
        from,
        to,
        workforce,
        productivity,
        round4(headlineFactor),
        labourCost,
        round2(weightedCpu),
        crews,
        round4(idleRatio),
        round4(otRatio),
        achievement,
        costSummary,
        round4(cumulativePct),
        dq);
  }

  // ---------- Aggregation helpers ----------

  private Map<UUID, DprManpowerAgg> computeAggByDpr(
      Map<UUID, List<DprManpower>> manpowerByDpr,
      Map<UUID, ManpowerFinancials> financialsById) {
    Map<UUID, DprManpowerAgg> out = new HashMap<>(manpowerByDpr.size());
    for (Map.Entry<UUID, List<DprManpower>> e : manpowerByDpr.entrySet()) {
      double manHours = 0d;
      double otHours = 0d;
      double idleHours = 0d;
      double cost = 0d;
      for (DprManpower m : e.getValue()) {
        int nos = m.getNos() != null ? m.getNos() : 0;
        double wh = m.getWorkingHours() != null ? m.getWorkingHours().doubleValue() : 0d;
        double oh = m.getOtHours() != null ? m.getOtHours().doubleValue() : 0d;
        double ih = m.getIdleHours() != null ? m.getIdleHours().doubleValue() : 0d;
        manHours += nos * wh;
        otHours += nos * oh;
        idleHours += nos * ih;
        if (m.getLineCost() != null) {
          cost += m.getLineCost().doubleValue();
        } else if (m.getUnitRate() != null) {
          // Role-only fallback: derive from snapshotted unit_rate + basis on the row.
          boolean hourly = "HOUR".equalsIgnoreCase(m.getUnitRateBasis());
          double rate = m.getUnitRate().doubleValue();
          cost += hourly
              ? nos * (wh + oh * OT_MULTIPLIER) * rate
              : nos * rate;
        } else {
          // Legacy resource-only fallback when no rate was snapshotted at save time.
          double hourly = effectiveHourlyRate(financialsById.get(m.getResourceId()));
          cost += nos * (wh + oh * OT_MULTIPLIER) * hourly;
        }
      }
      out.put(e.getKey(), new DprManpowerAgg(manHours, otHours, idleHours, cost));
    }
    return out;
  }

  /**
   * Group DPRs by activity and roll up qty + man-hours + nos-by-role + days-seen. Keys are
   * prefixed {@code "id:<uuid>"} when the DPR has a real activity_id, otherwise
   * {@code "name:<name>"} to keep legacy free-text rows from collapsing into the same bucket as
   * a real activity.
   */
  private Map<String, ActivityAgg> computeAggByActivity(
      List<DailyProgressReport> dprs,
      Map<UUID, DprManpowerAgg> aggByDpr,
      Map<UUID, List<DprManpower>> manpowerByDpr) {
    Map<String, ActivityAgg> out = new HashMap<>();
    for (DailyProgressReport d : dprs) {
      String key = d.getActivityId() != null
          ? "id:" + d.getActivityId()
          : "name:" + (d.getActivityName() != null ? d.getActivityName().toLowerCase() : "");
      double qty = d.getQtyExecuted() != null ? d.getQtyExecuted().doubleValue() : 0d;
      double manHours = aggByDpr.getOrDefault(d.getId(), new DprManpowerAgg(0, 0, 0, 0)).manHours();
      // Roll up nos per role from this DPR's manpower rows. role_id null → bucket under a
      // null sentinel so legacy untagged rows still contribute to totalNos.
      int dprNos = 0;
      Map<UUID, Integer> dprNosByRole = new HashMap<>();
      for (DprManpower m : manpowerByDpr.getOrDefault(d.getId(), List.of())) {
        int nos = m.getNos() != null ? m.getNos() : 0;
        if (nos == 0) continue;
        dprNos += nos;
        UUID rid = m.getRoleId();
        if (rid != null) dprNosByRole.merge(rid, nos, Integer::sum);
      }
      ActivityAgg existing = out.get(key);
      if (existing == null) {
        Set<LocalDate> days = new HashSet<>();
        if (d.getReportDate() != null) days.add(d.getReportDate());
        out.put(key, new ActivityAgg(d.getActivityName(), d.getUnit(), qty, manHours,
            dprNos, dprNosByRole, days));
      } else {
        existing.daysSeen().add(d.getReportDate());
        Map<UUID, Integer> merged = new HashMap<>(existing.nosByRole());
        dprNosByRole.forEach((k, v) -> merged.merge(k, v, Integer::sum));
        out.put(key, new ActivityAgg(
            existing.activityName(),
            existing.dprUnit() != null ? existing.dprUnit() : d.getUnit(),
            existing.qtyExecuted() + qty,
            existing.manHours() + manHours,
            existing.totalNos() + dprNos,
            merged,
            existing.daysSeen()));
      }
    }
    return out;
  }

  // ---------- Workforce utilisation (KPI 1.1) ----------

  /**
   * Nos-based workforce deployment: {@code Σ DPR nos ÷ Σ planned headcount} across manpower
   * assignments. Replaces the legacy hours-based formula. Hours are logging-only in the
   * current cost model and don't enter this metric.
   */
  private WorkforceUtilization computeWorkforceUtilization(
      List<DprManpower> manpowerRows,
      List<ResourceAssignment> assignments,
      Map<UUID, Resource> resourcesById,
      Set<UUID> deployedIdentities) {

    int actualNos = manpowerRows.stream()
        .mapToInt(m -> m.getNos() != null ? m.getNos() : 0).sum();

    int plannedNos = assignments.stream()
        .filter(ra -> isManpowerAssignment(ra, resourcesById))
        .mapToInt(ra -> ra.getHeadcount() != null ? ra.getHeadcount() : 0)
        .sum();

    double rawPct = plannedNos > 0 ? (double) actualNos / plannedNos : 0d;
    boolean overflow = rawPct > 1.0d;
    double cappedPct = Math.min(rawPct, 1.0d);

    int totalIdentities = (int) assignments.stream()
        .filter(ra -> isManpowerAssignment(ra, resourcesById))
        .map(ra -> ra.getRoleId() != null ? ra.getRoleId() : ra.getResourceId())
        .filter(java.util.Objects::nonNull)
        .distinct()
        .count();
    int activeIdentities = deployedIdentities.size();

    return new WorkforceUtilization(
        actualNos,
        plannedNos,
        round4(cappedPct),
        round4(rawPct),
        overflow,
        Math.max(totalIdentities, activeIdentities),
        activeIdentities,
        0);
  }

  /** True iff the assignment is manpower — role-only path OR legacy LABOR resource path. */
  private boolean isManpowerAssignment(ResourceAssignment ra, Map<UUID, Resource> resourcesById) {
    if (ra.getManpowerRoleRateId() != null) return true;
    if (ra.getResourceId() == null) return false;
    Resource r = resourcesById.get(ra.getResourceId());
    if (r == null) {
      r = resourceRepository.findById(ra.getResourceId()).orElse(null);
      if (r != null) resourcesById.put(r.getId(), r);
    }
    return r != null && r.getResourceType() != null
        && LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode());
  }

  // ---------- Productivity factor by activity (KPI 2.1 / 2.3) ----------

  private List<ProductivityFactorRow> computeProductivityFactor(
      Map<String, ActivityAgg> aggByActivity,
      Map<UUID, Activity> activitiesById,
      Map<UUID, BigDecimal> scQtyByActivity) {
    List<ProductivityFactorRow> rows = new ArrayList<>(aggByActivity.size());
    for (Map.Entry<String, ActivityAgg> e : aggByActivity.entrySet()) {
      ActivityAgg a = e.getValue();
      if (a.totalNos() <= 0) continue;
      Activity activity = resolveActivity(e.getKey(), activitiesById);
      ProductivityNorm norm = lookupManpowerNorm(activity);

      int productiveNos = a.totalNos();
      if (norm != null && norm.getRoleId() != null) {
        productiveNos = a.nosByRole().getOrDefault(norm.getRoleId(), 0);
      }

      // Crew-only output: subtract sub-contractor's contribution to qty_executed so this
      // metric describes supervisor's own crew productivity, not blended crew + SC.
      double scQty = 0d;
      if (activity != null && scQtyByActivity.containsKey(activity.getId())) {
        scQty = scQtyByActivity.get(activity.getId()).doubleValue();
      }
      double crewQty = Math.max(0d, a.qtyExecuted() - scQty);

      double actualPerManPerDay = productiveNos > 0 ? crewQty / productiveNos : 0d;

      double normValue = norm != null && norm.getOutputPerManPerDay() != null
          ? norm.getOutputPerManPerDay().doubleValue() : 0d;
      double factor = normValue > 0 && productiveNos > 0 ? actualPerManPerDay / normValue : 0d;

      String dprUnit = a.dprUnit();
      String normUnit = norm != null ? norm.getUnit() : null;
      boolean mismatch = dprUnit != null && normUnit != null
          && !dprUnit.equalsIgnoreCase(normUnit);

      rows.add(new ProductivityFactorRow(
          activity != null ? activity.getId() : null,
          activity != null ? activity.getName() : a.activityName(),
          dprUnit,
          normUnit,
          round4(actualPerManPerDay),
          round4(normValue),
          round4(factor),
          mismatch));
    }
    rows.sort(Comparator.comparingDouble(ProductivityFactorRow::factor));
    return rows;
  }

  private double computeHeadlineProductivityFactor(List<ProductivityFactorRow> rows) {
    List<ProductivityFactorRow> usable = rows.stream()
        .filter(r -> !r.unitMismatch())
        .filter(r -> r.normOutputPerManPerDay() > 0d)
        .toList();
    if (usable.isEmpty()) return 0d;
    return usable.stream().mapToDouble(ProductivityFactorRow::factor).average().orElse(0d);
  }

  // ---------- Labour cost per BOQ item (KPI 3.5) ----------

  private List<LabourCostPerUnitRow> computeLabourCostPerUnit(
      List<BoqItem> boq,
      List<DailyProgressReport> dprs,
      Map<UUID, DprManpowerAgg> aggByDpr,
      Map<UUID, BigDecimal> scQtyByBoqItem) {

    if (dprs.isEmpty()) return List.of();

    Map<UUID, double[]> aggByBoq = new HashMap<>(); // [labourCost, qtyExecuted]

    Map<UUID, BoqItem> boqById = boq.stream()
        .collect(Collectors.toMap(BoqItem::getId, b -> b, (a, b2) -> a));
    for (DailyProgressReport d : dprs) {
      BoqItem match = resolveBoqForDpr(d, boq, boqById);
      if (match == null) continue;
      double[] acc = aggByBoq.computeIfAbsent(match.getId(), k -> new double[2]);
      DprManpowerAgg agg = aggByDpr.getOrDefault(d.getId(), new DprManpowerAgg(0, 0, 0, 0));
      acc[0] += agg.cost();
      if (d.getQtyExecuted() != null) acc[1] += d.getQtyExecuted().doubleValue();
    }

    List<LabourCostPerUnitRow> rows = new ArrayList<>(aggByBoq.size());
    for (Map.Entry<UUID, double[]> e : aggByBoq.entrySet()) {
      BoqItem item = boq.stream().filter(b -> b.getId().equals(e.getKey())).findFirst().orElse(null);
      if (item == null) continue;
      double labourCost = e.getValue()[0];
      double qty = e.getValue()[1];

      // Subtract sub-contractor contribution so cost-per-unit reflects supervisor's crew
      // cost over supervisor's crew output. SC delivered units don't carry our manpower cost.
      double scQty = scQtyByBoqItem.containsKey(item.getId())
          ? scQtyByBoqItem.get(item.getId()).doubleValue() : 0d;
      double crewQty = Math.max(0d, qty - scQty);

      double cpu = crewQty > 0 ? labourCost / crewQty : 0d;
      rows.add(new LabourCostPerUnitRow(
          item.getId(),
          item.getItemNo(),
          item.getDescription(),
          item.getUnit(),
          round2(labourCost),
          round3(crewQty),    // surface crew-only qty so the table reads consistently
          round2(cpu)));
    }
    rows.sort(Comparator.comparingDouble(LabourCostPerUnitRow::costPerUnit).reversed());
    return rows;
  }

  private double computeWeightedAvgCostPerUnit(List<LabourCostPerUnitRow> rows) {
    double totalCost = 0d;
    double totalQty = 0d;
    for (LabourCostPerUnitRow r : rows) {
      totalCost += r.labourCost();
      totalQty += r.qtyExecuted();
    }
    return totalQty > 0d ? totalCost / totalQty : 0d;
  }

  /**
   * Salary normalisation: HOURLY → direct hourlyRate; otherwise fallback via salary type
   * (PERMANENT-equivalent /30/8, CONTRACT-equivalent /26/8, DAILY /8).
   */
  private static double effectiveHourlyRate(ManpowerFinancials f) {
    if (f == null) return 0d;
    if (f.getHourlyRate() != null && f.getHourlyRate().signum() > 0) {
      return f.getHourlyRate().doubleValue();
    }
    if (f.getBaseSalary() == null) return 0d;
    SalaryType t = f.getSalaryType();
    if (t == null) return 0d;
    return switch (t) {
      case MONTHLY -> f.getBaseSalary().doubleValue() / 30d / 8d;
      case DAILY -> f.getBaseSalary().doubleValue() / 8d;
      case HOURLY -> f.getBaseSalary().doubleValue();
    };
  }

  private static BoqItem matchBoqByActivityName(List<BoqItem> boq, String activityName) {
    if (activityName == null || activityName.isBlank()) return null;
    String needle = activityName.toLowerCase();
    for (BoqItem b : boq) {
      if (b.getItemNo() != null && needle.contains(b.getItemNo().toLowerCase())) return b;
    }
    for (BoqItem b : boq) {
      if (b.getDescription() != null && needle.contains(b.getDescription().toLowerCase())) return b;
    }
    return null;
  }

  /**
   * Resolve the BOQ item for a DPR. Prefers the hard FK {@link DailyProgressReport#getBoqItemId()}
   * (set when the supervisor picks a BOQ on the DPR form); falls back to fuzzy name matching on
   * legacy rows where the FK is null.
   */
  private static BoqItem resolveBoqForDpr(
      DailyProgressReport dpr, List<BoqItem> boq, Map<UUID, BoqItem> boqById) {
    if (dpr.getBoqItemId() != null) {
      BoqItem byFk = boqById.get(dpr.getBoqItemId());
      if (byFk != null) return byFk;
    }
    return matchBoqByActivityName(boq, dpr.getActivityName());
  }

  // ---------- Crew output rows ----------

  private List<CrewOutputRow> computeCrewOutput(
      Map<String, ActivityAgg> aggByActivity,
      Map<UUID, Activity> activitiesById) {
    List<CrewOutputRow> rows = new ArrayList<>(aggByActivity.size());
    for (Map.Entry<String, ActivityAgg> e : aggByActivity.entrySet()) {
      ActivityAgg agg = e.getValue();
      Activity activity = resolveActivity(e.getKey(), activitiesById);
      int days = agg.daysSeen().size();
      double actualPerDay = days > 0 ? agg.qtyExecuted() / days : 0d;
      ProductivityNorm norm = lookupManpowerNorm(activity);
      Integer crewSize = norm != null ? norm.getCrewSize() : null;
      double normPerDay = norm != null && norm.getOutputPerDay() != null
          ? norm.getOutputPerDay().doubleValue() : 0d;
      double dev = normPerDay > 0 ? (actualPerDay - normPerDay) / normPerDay : 0d;
      rows.add(new CrewOutputRow(
          activity != null ? activity.getId() : null,
          activity != null ? activity.getName() : agg.activityName(),
          crewSize,
          round4(actualPerDay),
          round4(normPerDay),
          round4(dev)));
    }
    rows.sort(Comparator.comparingDouble(CrewOutputRow::deviationPct));
    return rows;
  }

  // ---------- Idle Time Ratio (KPI 1.2) ----------

  private double computeIdleTimeRatioPct(Map<UUID, DprManpowerAgg> aggByDpr) {
    double idle = 0d;
    double productive = 0d;
    for (DprManpowerAgg a : aggByDpr.values()) {
      idle += a.idleHours();
      productive += a.manHours();
    }
    double total = idle + productive;
    return total > 0 ? idle / total : 0d;
  }

  // ---------- Overtime Ratio (KPI 1.3) ----------

  private double computeOvertimeRatioPct(Map<UUID, DprManpowerAgg> aggByDpr) {
    double regular = 0d;
    double ot = 0d;
    for (DprManpowerAgg a : aggByDpr.values()) {
      regular += a.manHours();
      ot += a.otHours();
    }
    double total = regular + ot;
    return total > 0 ? ot / total : 0d;
  }

  // ---------- Output Achievement % (KPI 2.2) ----------

  /**
   * Output Achievement % per activity = cumulative BOQ % complete
   * (= {@code qtyExecuted ÷ boqQty}). Mirrors the "% Complete" column on the BOQ table so the
   * Insights tile reconciles 1:1 with what supervisors see when they open a BOQ item. BOQ is
   * resolved via {@code DailyProgressReport.boqItemId} (hard FK) with fuzzy name match as a
   * legacy fallback.
   *
   * <p>The {@code actualDailyOutput} / {@code plannedDailyOutput} fields on
   * {@link OutputAchievementRow} are repurposed as {@code qtyExecuted} and {@code boqQty}
   * respectively — the response shape stays back-compat but the labels in the UI need a tweak.
   */
  private List<OutputAchievementRow> computeOutputAchievement(
      Map<String, ActivityAgg> aggByActivity,
      Map<UUID, Activity> activitiesById,
      Map<String, BoqItem> boqByActivityKey,
      int[] noBoqBaselineCount) {
    if (aggByActivity.isEmpty()) return List.of();

    List<OutputAchievementRow> rows = new ArrayList<>(aggByActivity.size());
    for (Map.Entry<String, ActivityAgg> e : aggByActivity.entrySet()) {
      Activity activity = resolveActivity(e.getKey(), activitiesById);
      if (activity == null) continue;
      ActivityAgg agg = e.getValue();

      BoqItem boq = boqByActivityKey.get(e.getKey());
      if (boq == null || boq.getBoqQty() == null || boq.getBoqQty().signum() <= 0) {
        noBoqBaselineCount[0]++;
        continue;
      }

      double qtyExecuted = agg.qtyExecuted();
      double boqQty = boq.getBoqQty().doubleValue();
      double pct = boqQty > 0 ? qtyExecuted / boqQty : 0d;

      rows.add(new OutputAchievementRow(
          activity.getId(),
          activity.getName(),
          round4(qtyExecuted),     // repurposed: actualDailyOutput → qtyExecuted
          round4(boqQty),          // repurposed: plannedDailyOutput → boqQty
          round4(pct)));
    }
    rows.sort(Comparator.comparingDouble(OutputAchievementRow::achievementPct));
    return rows;
  }

  /**
   * Resolve the BOQ for each unique activity key by sampling its DPRs' hard FK. First non-null
   * {@code boq_item_id} wins; activities whose DPRs all lack the FK fall back to fuzzy name.
   */
  private Map<String, BoqItem> resolveBoqByActivity(
      List<DailyProgressReport> dprs, List<BoqItem> boqItems, Map<UUID, BoqItem> boqById) {
    Map<String, BoqItem> out = new HashMap<>();
    for (DailyProgressReport d : dprs) {
      String key = d.getActivityId() != null
          ? "id:" + d.getActivityId()
          : "name:" + (d.getActivityName() != null ? d.getActivityName().toLowerCase() : "");
      if (out.containsKey(key)) continue;
      if (d.getBoqItemId() != null) {
        BoqItem b = boqById.get(d.getBoqItemId());
        if (b != null) {
          out.put(key, b);
          continue;
        }
      }
      BoqItem fuzzy = matchBoqByActivityName(boqItems, d.getActivityName());
      if (fuzzy != null) out.put(key, fuzzy);
    }
    return out;
  }

  // ---------- Labour Cost Summary (KPI 3.1 / 3.3 / 3.4 / 3.7) ----------

  /**
   * Computes Planned / Actual / Variance / LCPI / OT Cost % for the requested window.
   *
   * <p>PLC: for each LABOR resource_assignment, prorate
   * {@code planned_units × hourly_rate} by the overlap fraction of the assignment with the
   * window. {@code planned_units} stores total man-hours over the assignment period (locked
   * with user 2026-05-08).
   *
   * <p>ALC: pulls from existing per-DPR cost roll-up (already includes 2× OT premium when the
   * fallback path is hit; supervisor-entered {@code line_cost} is trusted as-is).
   *
   * <p>OT Cost %: computed independently as
   * {@code Σ (nos × ot_hrs × hourly_rate × 2.0) / Σ (nos × (working + ot×2.0) × hourly_rate)}.
   * Uses fallback rate even when {@code line_cost} was supervisor-entered, since the supervisor
   * value bundles regular and OT and we can't separate them.
   */
  private LabourCostSummary computeLabourCostSummary(
      UUID projectId, LocalDate from, LocalDate to,
      Map<UUID, Resource> resourcesById,
      Map<UUID, ManpowerFinancials> financialsById,
      Map<UUID, DprManpowerAgg> aggByDpr,
      Map<UUID, List<DprManpower>> manpowerByDpr) {

    // ---- PLC ----
    List<ResourceAssignment> assignments =
        resourceAssignmentRepository.findByProjectId(projectId);
    Set<UUID> activityIds = assignments.stream()
        .map(ResourceAssignment::getActivityId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    Map<UUID, Activity> activitiesForPlan = activityRepository.findAllById(activityIds).stream()
        .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

    // PLC = Σ assignments.planned_cost over manpower assignments. The cost rollup listener
    // already populates planned_cost as headcount × rate, matching the Resource Plan drawer.
    // Window-overlap proration is intentionally NOT applied: planned_cost is the committed
    // budget; the Insights window is a viewing filter, not a budget slicer.
    double plc = 0d;
    int activitiesWithPlan = 0;
    int missingPlan = 0;
    for (ResourceAssignment ra : assignments) {
      boolean isManpower;
      if (ra.getManpowerRoleRateId() != null) {
        isManpower = true;
      } else if (ra.getResourceId() != null) {
        Resource r = resourcesById.get(ra.getResourceId());
        if (r == null) {
          r = resourceRepository.findById(ra.getResourceId()).orElse(null);
          if (r != null) resourcesById.put(r.getId(), r);
        }
        isManpower = r != null && r.getResourceType() != null
            && LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode());
      } else {
        continue;
      }
      if (!isManpower) continue;
      if (ra.getPlannedCost() == null) { missingPlan++; continue; }
      plc += ra.getPlannedCost().doubleValue();
      activitiesWithPlan++;
    }

    // ---- ALC ---- Σ DPR line_cost (already nos × rate per DprCostFormulas).
    double alc = aggByDpr.values().stream().mapToDouble(DprManpowerAgg::cost).sum();

    // OT Cost % is meaningless under the nos × rate model (overtime hours never enter cost).
    // Kept on the response for back-compat but pinned to 0; the frontend tile has been removed.
    double otCostPct = 0d;

    double variance = plc - alc;
    double lcpi = alc > 0d ? plc / alc : 0d;

    return new LabourCostSummary(
        round2(plc),
        round2(alc),
        round2(variance),
        round4(lcpi),
        round4(otCostPct),
        activitiesWithPlan,
        missingPlan);
  }

  // ---------- Cumulative Progress Achievement % (KPI 2.7) ----------

  /**
   * Project-level cumulative progress = Σ qtyExecutedToDate ÷ Σ boqQty across BOQ items, qty-
   * weighted. Reads directly from {@link BoqItem#getQtyExecutedToDate()} so it stays in lock-step
   * with the BOQ table the user sees on the BOQ page — the per-DPR rollup that keeps
   * {@code qtyExecutedToDate} fresh is already wired via {@code DprBoqSyncListener}.
   *
   * <p>BOQ items with {@code boqQty = 0} or null are excluded from both numerator and denominator
   * to avoid skewing the percentage.
   */
  private double computeCumulativeProgressPct(
      UUID projectId, LocalDate from, LocalDate to,
      List<DailyProgressReport> windowDprs,
      Map<UUID, Activity> activitiesById,
      List<BoqItem> boqItems) {
    if (boqItems.isEmpty()) return 0d;
    double totalExecuted = 0d;
    double totalBoqQty = 0d;
    for (BoqItem b : boqItems) {
      if (b.getBoqQty() == null || b.getBoqQty().signum() <= 0) continue;
      totalBoqQty += b.getBoqQty().doubleValue();
      if (b.getQtyExecutedToDate() != null) {
        totalExecuted += b.getQtyExecutedToDate().doubleValue();
      }
    }
    return totalBoqQty > 0d ? totalExecuted / totalBoqQty : 0d;
  }

  // ---------- Data quality ----------

  private DataQuality buildDataQuality(
      Set<UUID> labourResourceIds,
      Map<UUID, Resource> resourcesById,
      Map<UUID, ManpowerAttendance> attendanceById,
      Map<UUID, ManpowerFinancials> financialsById,
      List<ProductivityFactorRow> productivityRows,
      int noNormActivityCount,
      int noBoqBaselineActivityCount) {
    List<String> missingRate = new ArrayList<>();
    List<String> missingAttendance = new ArrayList<>();
    for (UUID rid : labourResourceIds) {
      Resource r = resourcesById.get(rid);
      String code = r != null ? (r.getCode() != null ? r.getCode() : r.getName()) : rid.toString();
      ManpowerFinancials f = financialsById.get(rid);
      if (f == null
          || ((f.getHourlyRate() == null || f.getHourlyRate().signum() <= 0)
              && (f.getBaseSalary() == null || f.getBaseSalary().signum() <= 0))) {
        missingRate.add(code);
      }
      ManpowerAttendance a = attendanceById.get(rid);
      if (a == null || a.getWorkingHoursPerDay() == null) {
        missingAttendance.add(code);
      }
    }
    int mismatches = (int) productivityRows.stream().filter(ProductivityFactorRow::unitMismatch).count();
    return new DataQuality(
        missingRate.size(),
        missingRate.stream().sorted().limit(20).toList(),
        missingAttendance.size(),
        missingAttendance.stream().sorted().limit(20).toList(),
        mismatches,
        noNormActivityCount,
        noBoqBaselineActivityCount);
  }

  // ---------- Lookup helpers ----------

  private Activity resolveActivity(String key, Map<UUID, Activity> activitiesById) {
    if (key.startsWith("id:")) {
      return activitiesById.get(UUID.fromString(key.substring(3)));
    }
    return null;
  }

  /**
   * Resolve the manpower productivity norm for an activity via {@code work_activity_id}.
   * The legacy name-match (findByActivityNameIgnoreCase) hit only ~4% of activities because
   * BOQ-derived activity names rarely equal the master productivity_norms.activity_name; the
   * FK link is populated for 100% of activities and gives correct coverage.
   */
  private ProductivityNorm lookupManpowerNorm(Activity activity) {
    if (activity == null || activity.getWorkActivityId() == null) return null;
    return productivityNormRepository.findByWorkActivityId(activity.getWorkActivityId()).stream()
        .filter(n -> n.getNormType() == ProductivityNormType.MANPOWER)
        .findFirst()
        .orElse(null);
  }

  // ---------- Empty response ----------

  private ManpowerKpiResponse emptyResponse(UUID projectId, LocalDate from, LocalDate to) {
    return new ManpowerKpiResponse(
        projectId, from, to,
        new WorkforceUtilization(0, 0, 0, 0, false, 0, 0, 0),
        List.of(), 0d,
        List.of(), 0d,
        List.of(),
        0d, 0d,
        List.of(),
        new LabourCostSummary(0d, 0d, 0d, 0d, 0d, 0, 0),
        0d,
        new DataQuality(0, List.of(), 0, List.of(), 0, 0, 0));
  }

  // ---------- Misc ----------

  private static double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static double round3(double v) {
    return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
  }

  private static double round4(double v) {
    return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }

  private static double roundHours(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  @SuppressWarnings("unused")
  private void __referenceForImportSanity(ResourceType t) {}
}
