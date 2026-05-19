package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
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
      double actualHours,
      double availableHours,
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
      Set<LocalDate> daysSeen) {}

  // ---------- Public API ----------

  @Transactional(readOnly = true)
  public ManpowerKpiResponse compute(UUID projectId, LocalDate from, LocalDate to) {
    List<DailyProgressReport> dprs = dprRepository
        .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, from, to);

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
    Map<String, ActivityAgg> aggByActivity = computeAggByActivity(dprs, aggByDpr);
    Map<String, UUID> activityIdsByKey = aggByActivity.keySet().stream()
        .filter(k -> k.startsWith("id:"))
        .collect(Collectors.toMap(k -> k, k -> UUID.fromString(k.substring(3))));
    Map<UUID, Activity> activitiesById = activityRepository
        .findAllById(activityIdsByKey.values()).stream()
        .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

    // BOQ list — shared by Labour Cost / Unit and Output Achievement % (planned-daily baseline).
    List<BoqItem> boqItems = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);

    // KPI computations
    WorkforceUtilization workforce = computeWorkforceUtilization(
        dprs, manpowerByDpr, labourResourceIds, attendanceById, deployedIdentities);
    List<ProductivityFactorRow> productivity =
        computeProductivityFactor(aggByActivity, activitiesById);
    double headlineFactor = computeHeadlineProductivityFactor(productivity);
    List<LabourCostPerUnitRow> labourCost =
        computeLabourCostPerUnit(boqItems, dprs, aggByDpr);
    double weightedCpu = computeWeightedAvgCostPerUnit(labourCost);
    List<CrewOutputRow> crews = computeCrewOutput(aggByActivity, activitiesById);
    double idleRatio = computeIdleTimeRatioPct(aggByDpr);
    double otRatio = computeOvertimeRatioPct(aggByDpr);

    int[] noBoqBaselineCount = new int[1];
    List<OutputAchievementRow> achievement =
        computeOutputAchievement(aggByActivity, activitiesById, boqItems, noBoqBaselineCount);

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
   * Group DPRs by activity and roll up qty + man-hours + days-seen. Keys are prefixed
   * {@code "id:<uuid>"} when the DPR has a real activity_id, otherwise {@code "name:<name>"}
   * to keep legacy free-text rows from collapsing into the same bucket as a real activity.
   */
  private Map<String, ActivityAgg> computeAggByActivity(
      List<DailyProgressReport> dprs,
      Map<UUID, DprManpowerAgg> aggByDpr) {
    Map<String, ActivityAgg> out = new HashMap<>();
    for (DailyProgressReport d : dprs) {
      String key = d.getActivityId() != null
          ? "id:" + d.getActivityId()
          : "name:" + (d.getActivityName() != null ? d.getActivityName().toLowerCase() : "");
      double qty = d.getQtyExecuted() != null ? d.getQtyExecuted().doubleValue() : 0d;
      double manHours = aggByDpr.getOrDefault(d.getId(), new DprManpowerAgg(0, 0, 0, 0)).manHours();
      ActivityAgg existing = out.get(key);
      if (existing == null) {
        Set<LocalDate> days = new HashSet<>();
        if (d.getReportDate() != null) days.add(d.getReportDate());
        out.put(key, new ActivityAgg(d.getActivityName(), d.getUnit(), qty, manHours, days));
      } else {
        existing.daysSeen().add(d.getReportDate());
        out.put(key, new ActivityAgg(
            existing.activityName(),
            existing.dprUnit() != null ? existing.dprUnit() : d.getUnit(),
            existing.qtyExecuted() + qty,
            existing.manHours() + manHours,
            existing.daysSeen()));
      }
    }
    return out;
  }

  // ---------- Workforce utilisation (KPI 1.1) ----------

  private WorkforceUtilization computeWorkforceUtilization(
      List<DailyProgressReport> dprs,
      Map<UUID, List<DprManpower>> manpowerByDpr,
      Set<UUID> labourResourceIds,
      Map<UUID, ManpowerAttendance> attendanceById,
      Set<UUID> deployedIdentities) {

    // Productive man-hours: Σ nos × working_hours across all dpr_manpower rows. We treat
    // every dpr_manpower entry as labour (the table is for manpower deployment by trade).
    double actualHours = 0d;
    Map<UUID, Set<LocalDate>> daysByResource = new HashMap<>();   // legacy resource_id path
    double roleOnlyAvailableHours = 0d;                            // role-only (nos × 8) path
    Set<UUID> roleOnlyActiveIdentities = new HashSet<>();
    for (DailyProgressReport d : dprs) {
      List<DprManpower> rows = manpowerByDpr.getOrDefault(d.getId(), List.of());
      for (DprManpower m : rows) {
        int nos = m.getNos() != null ? m.getNos() : 0;
        double wh = m.getWorkingHours() != null ? m.getWorkingHours().doubleValue() : 0d;
        actualHours += nos * wh;
        if (m.getResourceId() != null && labourResourceIds.contains(m.getResourceId())
            && d.getReportDate() != null) {
          // Legacy per-Resource path: 1 person × attendance.workingHoursPerDay × days seen.
          daysByResource.computeIfAbsent(m.getResourceId(), k -> new HashSet<>())
              .add(d.getReportDate());
        } else {
          // Role-only path: each DPR row already aggregates a crew (nos workers for one day).
          // Available time for that crew = nos × DEFAULT_HOURS_PER_DAY. Compares directly with
          // actual nos × workingHours, so a half-day shift lands utilisation at 50%.
          roleOnlyAvailableHours += nos * DEFAULT_HOURS_PER_DAY;
          UUID identity = m.getRoleId() != null ? m.getRoleId() : m.getResourceId();
          if (identity != null) roleOnlyActiveIdentities.add(identity);
        }
      }
    }

    int missingAttendance = 0;
    double legacyAvailableHours = 0d;
    for (Map.Entry<UUID, Set<LocalDate>> e : daysByResource.entrySet()) {
      ManpowerAttendance a = attendanceById.get(e.getKey());
      double hpd = a != null && a.getWorkingHoursPerDay() != null
          ? a.getWorkingHoursPerDay().doubleValue()
          : DEFAULT_HOURS_PER_DAY;
      if (a == null || a.getWorkingHoursPerDay() == null) missingAttendance++;
      legacyAvailableHours += hpd * e.getValue().size();
    }
    double availableHours = legacyAvailableHours + roleOnlyAvailableHours;

    double rawPct = availableHours > 0 ? actualHours / availableHours : 0d;
    boolean overflow = rawPct > 1.0d;
    double cappedPct = Math.min(rawPct, 1.0d);

    int totalIdentities = Math.max(deployedIdentities.size(),
        labourResourceIds.size() + roleOnlyActiveIdentities.size());
    int activeIdentities = daysByResource.size() + roleOnlyActiveIdentities.size();

    return new WorkforceUtilization(
        roundHours(actualHours),
        roundHours(availableHours),
        round4(cappedPct),
        round4(rawPct),
        overflow,
        totalIdentities,
        activeIdentities,
        missingAttendance);
  }

  // ---------- Productivity factor by activity (KPI 2.1 / 2.3) ----------

  private List<ProductivityFactorRow> computeProductivityFactor(
      Map<String, ActivityAgg> aggByActivity,
      Map<UUID, Activity> activitiesById) {
    List<ProductivityFactorRow> rows = new ArrayList<>(aggByActivity.size());
    for (Map.Entry<String, ActivityAgg> e : aggByActivity.entrySet()) {
      ActivityAgg a = e.getValue();
      if (a.manHours() <= 0d) continue;
      Activity activity = resolveActivity(e.getKey(), activitiesById);
      double daysEquivalent = a.manHours() / DEFAULT_HOURS_PER_DAY;
      double actualPerManPerDay = daysEquivalent > 0 ? a.qtyExecuted() / daysEquivalent : 0d;

      ProductivityNorm norm = lookupManpowerNorm(activity);
      double normValue = norm != null && norm.getOutputPerManPerDay() != null
          ? norm.getOutputPerManPerDay().doubleValue() : 0d;
      double factor = normValue > 0 ? actualPerManPerDay / normValue : 0d;

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
      Map<UUID, DprManpowerAgg> aggByDpr) {

    if (dprs.isEmpty()) return List.of();

    Map<UUID, double[]> aggByBoq = new HashMap<>(); // [labourCost, qtyExecuted]

    for (DailyProgressReport d : dprs) {
      BoqItem match = matchBoqByActivityName(boq, d.getActivityName());
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
      double cpu = qty > 0 ? labourCost / qty : 0d;
      rows.add(new LabourCostPerUnitRow(
          item.getId(),
          item.getItemNo(),
          item.getDescription(),
          item.getUnit(),
          round2(labourCost),
          round3(qty),
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
   * KPI 2.2 — Output Achievement % = Actual Output ÷ Planned Daily Output × 100. Both arms
   * must be in the same physical unit (Cum, Sqm, MT). Planned daily output is derived from
   * the matched BOQ row: {@code boq_qty ÷ activity.original_duration}. The previous
   * implementation divided by {@code resource_assignments.planned_units} (man-hours) and
   * compared against DPR qty (Cum/Sqm) — a unit-category mismatch that produced 900–2272%.
   *
   * <p>Activities with no BOQ match are skipped from the result list and counted into
   * {@code noBoqBaselineCount} so the data-quality banner can surface the gap.
   */
  private List<OutputAchievementRow> computeOutputAchievement(
      Map<String, ActivityAgg> aggByActivity,
      Map<UUID, Activity> activitiesById,
      List<BoqItem> boqItems,
      int[] noBoqBaselineCount) {
    if (aggByActivity.isEmpty()) return List.of();

    List<OutputAchievementRow> rows = new ArrayList<>(aggByActivity.size());
    for (Map.Entry<String, ActivityAgg> e : aggByActivity.entrySet()) {
      Activity activity = resolveActivity(e.getKey(), activitiesById);
      if (activity == null) continue;
      ActivityAgg agg = e.getValue();
      Double duration = activity.getOriginalDuration();
      if (duration == null || duration <= 0d) continue;
      int observedDays = agg.daysSeen().size();
      if (observedDays == 0) continue;

      BoqItem boq = matchBoqByActivityName(boqItems, activity.getName());
      if (boq == null || boq.getBoqQty() == null || boq.getBoqQty().signum() <= 0) {
        noBoqBaselineCount[0]++;
        continue;
      }

      double actualDaily = agg.qtyExecuted() / observedDays;
      double plannedDaily = boq.getBoqQty().doubleValue() / duration;
      double pct = plannedDaily > 0 ? actualDaily / plannedDaily : 0d;

      rows.add(new OutputAchievementRow(
          activity.getId(),
          activity.getName(),
          round4(actualDaily),
          round4(plannedDaily),
          round4(pct)));
    }
    rows.sort(Comparator.comparingDouble(OutputAchievementRow::achievementPct));
    return rows;
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

    double plc = 0d;
    int activitiesWithPlan = 0;
    int missingPlan = 0;
    Set<UUID> labourResourcesNeeded = new HashSet<>();
    for (ResourceAssignment ra : assignments) {
      if (ra.getResourceId() == null) continue;
      Resource r = resourcesById.get(ra.getResourceId());
      if (r == null) {
        r = resourceRepository.findById(ra.getResourceId()).orElse(null);
        if (r != null) resourcesById.put(r.getId(), r);
      }
      if (r == null || r.getResourceType() == null
          || !LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode())) continue;
      labourResourcesNeeded.add(r.getId());
    }
    Set<UUID> missingFinIds = labourResourcesNeeded.stream()
        .filter(id -> !financialsById.containsKey(id)).collect(Collectors.toSet());
    if (!missingFinIds.isEmpty()) {
      financialsRepository.findAllById(missingFinIds).forEach(
          f -> financialsById.put(f.getResourceId(), f));
    }

    for (ResourceAssignment ra : assignments) {
      if (ra.getPlannedUnits() == null) continue;
      Activity activity = activitiesForPlan.get(ra.getActivityId());
      if (activity == null) { missingPlan++; continue; }
      Double duration = activity.getOriginalDuration();
      if (duration == null || duration <= 0d) { missingPlan++; continue; }
      LocalDate aStart = ra.getPlannedStartDate() != null
          ? ra.getPlannedStartDate() : activity.getPlannedStartDate();
      LocalDate aFinish = ra.getPlannedFinishDate() != null
          ? ra.getPlannedFinishDate() : activity.getPlannedFinishDate();
      if (aStart == null || aFinish == null) { missingPlan++; continue; }
      LocalDate overlapStart = aStart.isAfter(from) ? aStart : from;
      LocalDate overlapEnd = aFinish.isBefore(to) ? aFinish : to;
      if (overlapEnd.isBefore(overlapStart)) continue;
      double overlapDays = overlapEnd.toEpochDay() - overlapStart.toEpochDay() + 1;
      double overlapPct = Math.min(1.0d, overlapDays / duration);

      double rate = 0d;
      if (ra.getManpowerRoleRateId() != null) {
        // Role-only path: assignment carries its own snapshot effective_rate.
        rate = ra.getEffectiveRate() != null ? ra.getEffectiveRate().doubleValue() : 0d;
      } else if (ra.getResourceId() != null) {
        Resource r = resourcesById.get(ra.getResourceId());
        if (r == null || r.getResourceType() == null
            || !LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode())) continue;
        rate = effectiveHourlyRate(financialsById.get(r.getId()));
      } else {
        continue;
      }
      if (rate <= 0d) continue;
      double assignmentPlc = ra.getPlannedUnits() * rate * overlapPct;
      plc += assignmentPlc;
      activitiesWithPlan++;
    }

    // ---- ALC ---- (already includes 2× OT in fallback path)
    double alc = aggByDpr.values().stream().mapToDouble(DprManpowerAgg::cost).sum();

    // ---- OT Cost % ----
    double otPremiumPay = 0d;
    double totalWageBill = 0d;
    for (List<DprManpower> rows : manpowerByDpr.values()) {
      for (DprManpower m : rows) {
        // Prefer the row's snapshotted unit_rate (set at DPR save for role-only and legacy
        // assignments alike). Fall back to manpower_financials lookup for old rows.
        double rate = 0d;
        boolean hourlyBasis = false;
        if (m.getUnitRate() != null) {
          rate = m.getUnitRate().doubleValue();
          hourlyBasis = "HOUR".equalsIgnoreCase(m.getUnitRateBasis());
        } else if (m.getResourceId() != null) {
          rate = effectiveHourlyRate(financialsById.get(m.getResourceId()));
          hourlyBasis = true;
        }
        if (rate <= 0d) continue;
        int nos = m.getNos() != null ? m.getNos() : 0;
        double wh = m.getWorkingHours() != null ? m.getWorkingHours().doubleValue() : 0d;
        double oh = m.getOtHours() != null ? m.getOtHours().doubleValue() : 0d;
        if (hourlyBasis) {
          otPremiumPay += nos * oh * rate * OT_MULTIPLIER;
          totalWageBill += nos * (wh + oh * OT_MULTIPLIER) * rate;
        } else {
          // DAY-basis rates can't be cleanly split into regular vs OT. Treat working_hours as a
          // full day and any OT hours as a proportional premium on the day rate.
          double dayRate = rate;
          double otPremium = oh > 0 ? (dayRate / DEFAULT_HOURS_PER_DAY) * oh * OT_MULTIPLIER : 0d;
          otPremiumPay += nos * otPremium;
          totalWageBill += nos * (dayRate + otPremium);
        }
      }
    }
    double otCostPct = totalWageBill > 0d ? otPremiumPay / totalWageBill : 0d;

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
   * Linear-interpolation fallback (locked with user 2026-05-08): planned cumulative qty for
   * each activity = {@code boq_qty × min(1, days_elapsed / original_duration)}. Replaced by
   * activity_progress_baselines snapshots when Phase 2C ships.
   *
   * <p>Returned value is qty-weighted average across activities with a matched BOQ row.
   * Activities without BOQ are skipped (and already counted by {@code noBoqBaselineCount} in
   * the data-quality block).
   */
  private double computeCumulativeProgressPct(
      UUID projectId, LocalDate from, LocalDate to,
      List<DailyProgressReport> windowDprs,
      Map<UUID, Activity> activitiesById,
      List<BoqItem> boqItems) {
    if (windowDprs.isEmpty()) return 0d;

    // Cumulative actual qty per activity = ALL DPRs up to {@code to}, not just the window.
    List<DailyProgressReport> cumulativeDprs = dprRepository
        .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, LocalDate.of(1900, 1, 1), to);
    Map<UUID, Double> actualByActivity = new HashMap<>();
    for (DailyProgressReport d : cumulativeDprs) {
      if (d.getActivityId() == null || d.getQtyExecuted() == null) continue;
      actualByActivity.merge(d.getActivityId(), d.getQtyExecuted().doubleValue(), Double::sum);
    }
    if (actualByActivity.isEmpty()) return 0d;

    // For weighting, total planned across qualifying activities.
    double totalActual = 0d;
    double totalPlanned = 0d;
    for (Map.Entry<UUID, Double> e : actualByActivity.entrySet()) {
      Activity activity = activitiesById.get(e.getKey());
      if (activity == null) continue;
      Double duration = activity.getOriginalDuration();
      if (duration == null || duration <= 0d) continue;
      LocalDate plannedStart = activity.getPlannedStartDate();
      if (plannedStart == null) continue;

      BoqItem boq = matchBoqByActivityName(boqItems, activity.getName());
      if (boq == null || boq.getBoqQty() == null || boq.getBoqQty().signum() <= 0) continue;

      double daysElapsed = Math.max(0d, to.toEpochDay() - plannedStart.toEpochDay() + 1);
      double progressPct = Math.min(1.0d, daysElapsed / duration);
      double plannedCumQty = boq.getBoqQty().doubleValue() * progressPct;
      if (plannedCumQty <= 0d) continue;

      totalActual += e.getValue();
      totalPlanned += plannedCumQty;
    }
    return totalPlanned > 0d ? totalActual / totalPlanned : 0d;
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
