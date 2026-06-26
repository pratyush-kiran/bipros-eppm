package com.bipros.ai.query;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.domain.model.DailyActivityResourceOutput;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.udf.application.dto.FormulaResultDto;
import com.bipros.udf.application.service.FormulaEngine;
import com.bipros.udf.domain.model.FormulaOverride;
import com.bipros.udf.domain.repository.FormulaOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Computes a {@link SupervisorPerformance} rollup for a single supervisor.
 * Reused by {@code SupervisorTool} (single supervisor) and
 * {@code CompareSupervisorsTool} (multi-supervisor side-by-side) so the metric
 * definitions stay identical.
 *
 * <p>Activity scope is enumerated via {@code Activity.responsibleResourceId} —
 * the cached supervisor cache populated by the bulk-supervisor-assignment flow.
 * DPRs are joined to those activities by {@code activity_id}; the legacy
 * {@code supervisor_name} string-match path is used only as a fallback when the
 * resource-id path is empty (back-compat for DPRs filed before the supervisor
 * field existed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorPerformanceCalculator {

  private final ActivityRepository activityRepository;
  private final ResourceAssignmentRepository assignmentRepository;
  private final EvmCalculationRepository evmRepository;
  private final ActivityExpenseRepository expenseRepository;
  private final DailyProgressReportRepository dprRepository;
  private final DailyActivityResourceOutputRepository outputRepository;
  private final ResourceRepository resourceRepository;
  private final ResourceContextFacade facade;
  private final FormulaEngine formulaEngine;
  private final FormulaOverrideRepository formulaOverrideRepository;

  // Cost formula codes evaluated per supervisor. Order matters only for diagnostics.
  private static final List<String> COST_FORMULA_CODES = List.of(
      "RES_PLANNED_COST",
      "RES_ACTUAL_COST",
      "RES_REMAINING_COST",
      "RES_AT_COMPLETION_COST",
      "SUP_COST_VARIANCE",
      "SUP_COST_VARIANCE_PCT");

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public SupervisorPerformance compute(
      UUID projectId, UUID supervisorResourceId, LocalDate dateFrom, LocalDate dateTo) {

    Resource supervisor =
        supervisorResourceId == null
            ? null
            : resourceRepository.findById(supervisorResourceId).orElse(null);
    Optional<ResourceProfile> profileOpt =
        supervisor == null
            ? Optional.empty()
            : facade.loadProfile(
                supervisor.getId(),
                EnumSet.of(
                    ResourceContextFacade.Include.MANPOWER, ResourceContextFacade.Include.HIERARCHY));
    ResourceProfile profile = profileOpt.orElse(null);
    String supervisorName =
        profile != null && profile.manpower() != null && profile.manpower().fullName() != null
            ? profile.manpower().fullName()
            : (supervisor != null ? supervisor.getName() : null);
    String supervisorCode = supervisor != null ? supervisor.getCode() : null;

    List<Activity> supervisedActivities =
        supervisor == null
            ? List.of()
            : activityRepository.findByProjectIdAndResponsibleResourceId(projectId, supervisor.getId());
    List<UUID> supervisedActivityIds =
        supervisedActivities.stream().map(Activity::getId).toList();

    SupervisorPerformance.ActivityScope activityScope = computeActivityScope(supervisedActivities);
    SupervisorPerformance.CostRollup costRollup = computeCostRollup(projectId, supervisedActivityIds);
    SupervisorPerformance.EvmRollup evmRollup =
        computeEvmRollup(projectId, supervisedActivities, supervisedActivityIds);

    Set<UUID> teamResourceIds = new HashSet<>();
    if (profile != null) {
      for (ResourceProfile.Subordinate s : profile.subordinates()) teamResourceIds.add(s.resourceId());
    }

    DprAggregation dprAgg =
        aggregateDprs(
            projectId, supervisedActivityIds, supervisorName, dateFrom, dateTo, teamResourceIds);

    int teamSize = profile == null ? 0 : profile.subordinates().size();

    return new SupervisorPerformance(
        supervisor != null ? supervisor.getId() : null,
        supervisorCode,
        supervisorName,
        dateFrom,
        dateTo,
        teamSize,
        activityScope,
        costRollup,
        evmRollup,
        dprAgg.dprRollup,
        dprAgg.topActivities,
        dprAgg.topMembers);
  }

  // ------------------------------------------------------------------------
  // Activity scope
  // ------------------------------------------------------------------------

  private SupervisorPerformance.ActivityScope computeActivityScope(List<Activity> activities) {
    int notStarted = 0;
    int inProgress = 0;
    int completed = 0;
    int delayed = 0;
    double pctSum = 0;
    int pctCount = 0;
    LocalDate today = LocalDate.now();
    List<String> codes = new ArrayList<>();
    for (Activity a : activities) {
      ActivityStatus status = a.getStatus();
      if (status == ActivityStatus.NOT_STARTED) notStarted++;
      else if (status == ActivityStatus.IN_PROGRESS) inProgress++;
      else if (status == ActivityStatus.COMPLETED) completed++;

      boolean isDelayed = false;
      if (a.getActualFinishDate() != null
          && a.getPlannedFinishDate() != null
          && a.getActualFinishDate().isAfter(a.getPlannedFinishDate())) {
        isDelayed = true;
      } else if (a.getActualFinishDate() == null
          && a.getPlannedFinishDate() != null
          && a.getPlannedFinishDate().isBefore(today)
          && (a.getPercentComplete() == null || a.getPercentComplete() < 100.0)) {
        isDelayed = true;
      }
      if (isDelayed) delayed++;

      if (a.getPercentComplete() != null) {
        pctSum += a.getPercentComplete();
        pctCount++;
      }
      if (codes.size() < 10 && a.getCode() != null) codes.add(a.getCode());
    }
    Double avgPct = pctCount == 0 ? null : pctSum / pctCount;
    return new SupervisorPerformance.ActivityScope(
        activities.size(), notStarted, inProgress, completed, delayed, avgPct, codes);
  }

  // ------------------------------------------------------------------------
  // Cost rollup (from ResourceAssignments on supervised activities)
  //
  // All money figures are evaluated through {@link FormulaEngine} so the
  // project's master + override formulas drive the result. Each evaluation
  // falls back to the legacy hard-coded math on engine error, so a missing
  // master row or a malformed override never breaks the AI tool. The seeded
  // master expressions are mathematically identical to the legacy code, so
  // numbers stay bit-identical for projects with no override.
  // ------------------------------------------------------------------------

  // Package-private so SupervisorPerformanceCalculatorTest can drive this path directly
  // without standing up the full mock graph required by the public {@link #compute} entrypoint.
  SupervisorPerformance.CostRollup computeCostRollup(UUID projectId, List<UUID> activityIds) {
    if (activityIds.isEmpty()) {
      return new SupervisorPerformance.CostRollup(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
          List.of());
    }
    List<ResourceAssignment> assignments = assignmentRepository.findByActivityIdIn(activityIds);
    BigDecimal planned = BigDecimal.ZERO;
    BigDecimal actual = BigDecimal.ZERO;
    BigDecimal remaining = BigDecimal.ZERO;
    BigDecimal atCompletion = BigDecimal.ZERO;

    for (ResourceAssignment ra : assignments) {
      // Derive the unit rate the same way the assignment was originally priced
      // (planned_cost = rate × planned_units). When units are zero/null we can't
      // recover a rate; the formula evaluation will fall back to the stored cost.
      BigDecimal plannedUnits = nvl(toBigDecimal(ra.getPlannedUnits()));
      BigDecimal actualUnits = nvl(toBigDecimal(ra.getActualUnits()));
      BigDecimal remainingUnits = nvl(toBigDecimal(ra.getRemainingUnits()));
      BigDecimal rate = deriveRate(ra, plannedUnits, actualUnits);

      Map<String, BigDecimal> rowCtx = Map.of(
          "RATE", nvl(rate),
          "PLANNED_UNITS", plannedUnits,
          "ACTUAL_UNITS", actualUnits,
          "REMAINING_UNITS", remainingUnits);

      BigDecimal raPlanned = evalOrFallback(
          "RES_PLANNED_COST", projectId, rowCtx, () -> nvl(ra.getPlannedCost()));
      BigDecimal raActual = evalOrFallback(
          "RES_ACTUAL_COST", projectId, rowCtx, () -> nvl(ra.getActualCost()));
      BigDecimal raRemaining = evalOrFallback(
          "RES_REMAINING_COST", projectId, rowCtx, () -> nvl(ra.getRemainingCost()));

      Map<String, BigDecimal> atcCtx = Map.of(
          "ACTUAL_COST", nvl(raActual),
          "REMAINING_COST", nvl(raRemaining));
      BigDecimal raAtCompletion = evalOrFallback(
          "RES_AT_COMPLETION_COST", projectId, atcCtx,
          () -> nvl(ra.getAtCompletionCost()).signum() == 0
              ? nvl(raActual).add(nvl(raRemaining))
              : nvl(ra.getAtCompletionCost()));

      planned = planned.add(nvl(raPlanned));
      actual = actual.add(nvl(raActual));
      remaining = remaining.add(nvl(raRemaining));
      atCompletion = atCompletion.add(nvl(raAtCompletion));
    }

    // Final snapshots so the variance lambdas can capture them.
    final BigDecimal totalPlanned = planned;
    final BigDecimal totalActual = actual;

    Map<String, BigDecimal> varCtx = Map.of("ACTUAL", totalActual, "PLANNED", totalPlanned);
    BigDecimal variance = evalOrFallback(
        "SUP_COST_VARIANCE", projectId, varCtx, () -> totalActual.subtract(totalPlanned));

    BigDecimal variancePctBd = evalOrFallback(
        "SUP_COST_VARIANCE_PCT", projectId, varCtx,
        () -> totalPlanned.signum() == 0
            ? null
            : totalActual.subtract(totalPlanned).multiply(HUNDRED).divide(totalPlanned, 4, RoundingMode.HALF_UP));
    Double variancePct = (variancePctBd == null || totalPlanned.signum() == 0)
        ? null
        : variancePctBd.doubleValue();

    List<String> overriddenCodes = collectActiveOverrides(projectId);

    return new SupervisorPerformance.CostRollup(
        totalPlanned, totalActual, remaining, atCompletion, variance, variancePct, overriddenCodes);
  }

  /**
   * Derive the unit rate used when the assignment was priced. Equivalent to
   * {@code planned_cost / planned_units} in the default case; falls back to the
   * actuals path when only those are available. Returns {@code null} when no
   * rate can be recovered (zero units everywhere). The caller's formula
   * evaluation then falls back to the stored cost values.
   */
  private static BigDecimal deriveRate(ResourceAssignment ra, BigDecimal plannedUnits, BigDecimal actualUnits) {
    if (ra.getPlannedCost() != null && plannedUnits.signum() > 0) {
      return ra.getPlannedCost().divide(plannedUnits, 8, RoundingMode.HALF_UP);
    }
    if (ra.getActualCost() != null && actualUnits.signum() > 0) {
      return ra.getActualCost().divide(actualUnits, 8, RoundingMode.HALF_UP);
    }
    return null;
  }

  /**
   * Returns the cost-formula codes that have an active, in-window project override
   * for the given project. Used to surface override disclosure to the AI tools.
   */
  private List<String> collectActiveOverrides(UUID projectId) {
    if (projectId == null) return List.of();
    Set<String> codes = new TreeSet<>();
    LocalDate today = LocalDate.now();
    for (String code : COST_FORMULA_CODES) {
      Optional<FormulaOverride> opt = formulaOverrideRepository.findByFormulaCodeAndProjectId(code, projectId);
      if (opt.isEmpty()) continue;
      FormulaOverride o = opt.get();
      if (!Boolean.TRUE.equals(o.getIsActive())) continue;
      if (o.getEffectiveFrom() != null && today.isBefore(o.getEffectiveFrom())) continue;
      if (o.getEffectiveTo() != null && today.isAfter(o.getEffectiveTo())) continue;
      codes.add(code);
    }
    return List.copyOf(codes);
  }

  // ---- Formula evaluation helpers (mirrors EvmServiceHelper pattern) ----

  private BigDecimal evalOrFallback(String code, UUID projectId,
                                    Map<String, BigDecimal> ctx, Supplier<BigDecimal> fallback) {
    BigDecimal result = safeEval(code, projectId, ctx);
    return result != null ? result : fallback.get();
  }

  private BigDecimal safeEval(String code, UUID projectId, Map<String, BigDecimal> ctx) {
    if (formulaEngine == null) return null;
    try {
      FormulaResultDto result = formulaEngine.evaluate(code, projectId, ctx);
      if (result.isError()) {
        log.debug("Formula {} returned error for project {}: {}", code, projectId, result.getErrorMessage());
        return null;
      }
      return result.getValue();
    } catch (Exception e) {
      log.debug("Formula {} evaluation failed for project {}: {}", code, projectId, e.getMessage());
      return null;
    }
  }

  private static BigDecimal nvl(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal toBigDecimal(Double d) {
    return d == null ? null : BigDecimal.valueOf(d);
  }

  // ------------------------------------------------------------------------
  // EVM rollup — prefer evm_calculations latest-per-activity, fall back to
  // ActivityExpense (EV proxy = budgeted * pct/100)
  // ------------------------------------------------------------------------

  private SupervisorPerformance.EvmRollup computeEvmRollup(
      UUID projectId, List<Activity> activities, List<UUID> activityIds) {
    if (activities.isEmpty()) {
      return new SupervisorPerformance.EvmRollup(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          null,
          null,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          0,
          "none");
    }
    BigDecimal bac = BigDecimal.ZERO;
    BigDecimal pv = BigDecimal.ZERO;
    BigDecimal ev = BigDecimal.ZERO;
    BigDecimal ac = BigDecimal.ZERO;
    int withEvm = 0;
    boolean usedEvm = false;
    boolean usedExpense = false;

    Map<UUID, ActivityExpense> expensesByActivity = new HashMap<>();
    if (!activityIds.isEmpty()) {
      List<ActivityExpense> expenses =
          expenseRepository.findByProjectIdAndActivityIdIn(projectId, activityIds);
      for (ActivityExpense e : expenses) {
        if (e.getActivityId() == null) continue;
        // If multiple expense rows per activity, keep the largest budget
        ActivityExpense prev = expensesByActivity.get(e.getActivityId());
        if (prev == null
            || (e.getBudgetedCost() != null
                && (prev.getBudgetedCost() == null
                    || e.getBudgetedCost().compareTo(prev.getBudgetedCost()) > 0))) {
          expensesByActivity.put(e.getActivityId(), e);
        }
      }
    }

    for (Activity a : activities) {
      Optional<EvmCalculation> evmOpt =
          evmRepository.findTopByProjectIdAndActivityIdOrderByDataDateDesc(projectId, a.getId());
      if (evmOpt.isPresent()) {
        EvmCalculation calc = evmOpt.get();
        if (calc.getBudgetAtCompletion() != null) bac = bac.add(calc.getBudgetAtCompletion());
        if (calc.getPlannedValue() != null) pv = pv.add(calc.getPlannedValue());
        if (calc.getEarnedValue() != null) ev = ev.add(calc.getEarnedValue());
        if (calc.getActualCost() != null) ac = ac.add(calc.getActualCost());
        withEvm++;
        usedEvm = true;
        continue;
      }
      ActivityExpense exp = expensesByActivity.get(a.getId());
      if (exp == null) continue;
      BigDecimal budget = exp.getBudgetedCost() != null ? exp.getBudgetedCost() : BigDecimal.ZERO;
      BigDecimal actual = exp.getActualCost() != null ? exp.getActualCost() : BigDecimal.ZERO;
      Double pct =
          exp.getPercentComplete() != null
              ? exp.getPercentComplete()
              : (a.getPercentComplete() != null ? a.getPercentComplete() : 0.0);
      bac = bac.add(budget);
      pv = pv.add(budget); // PV proxy: assume planned = budget when no time-phased PV available
      ev = ev.add(budget.multiply(BigDecimal.valueOf(pct / 100.0)));
      ac = ac.add(actual);
      usedExpense = true;
    }

    Double cpi = ac.signum() == 0 ? null : ev.divide(ac, 4, RoundingMode.HALF_UP).doubleValue();
    Double spi = pv.signum() == 0 ? null : ev.divide(pv, 4, RoundingMode.HALF_UP).doubleValue();
    BigDecimal cv = ev.subtract(ac);
    BigDecimal sv = ev.subtract(pv);
    String source = usedEvm && usedExpense ? "mixed" : usedEvm ? "evm" : usedExpense ? "expense" : "none";
    return new SupervisorPerformance.EvmRollup(bac, pv, ev, ac, cpi, spi, cv, sv, withEvm, source);
  }

  // ------------------------------------------------------------------------
  // DPR / productivity aggregation
  // ------------------------------------------------------------------------

  private record DprAggregation(
      SupervisorPerformance.DprRollup dprRollup,
      List<SupervisorPerformance.ActivityTopRollup> topActivities,
      List<SupervisorPerformance.MemberRollup> topMembers) {}

  private DprAggregation aggregateDprs(
      UUID projectId,
      List<UUID> supervisedActivityIds,
      String supervisorName,
      LocalDate from,
      LocalDate to,
      Set<UUID> teamResourceIds) {

    List<DailyProgressReport> all =
        dprRepository.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, DprApprovalStatus.APPROVED, from, to);

    Set<UUID> supervisedSet = new HashSet<>(supervisedActivityIds);
    int dprCount = 0;
    Set<LocalDate> reportDates = new HashSet<>();
    Set<String> activitiesTouched = new HashSet<>();
    BigDecimal totalQty = BigDecimal.ZERO;
    Map<String, ActivityRoll> activityRoll = new LinkedHashMap<>();
    boolean matchedById = false;
    boolean matchedByName = false;

    for (DailyProgressReport d : all) {
      boolean matchById =
          d.getActivityId() != null && supervisedSet.contains(d.getActivityId());
      boolean matchByName =
          !matchById
              && supervisorName != null
              && d.getSupervisorName() != null
              && (d.getSupervisorName().equalsIgnoreCase(supervisorName)
                  || d.getSupervisorName().toLowerCase().contains(supervisorName.toLowerCase()));
      if (!matchById && !matchByName) continue;
      if (matchById) matchedById = true;
      else matchedByName = true;
      dprCount++;
      if (d.getReportDate() != null) reportDates.add(d.getReportDate());
      if (d.getActivityName() != null) activitiesTouched.add(d.getActivityName());
      if (d.getQtyExecuted() != null) totalQty = totalQty.add(d.getQtyExecuted());
      if (d.getActivityName() != null) {
        activityRoll
            .computeIfAbsent(d.getActivityName(), k -> new ActivityRoll(d.getActivityId(), k))
            .add(d);
      }
    }

    BigDecimal totalHours = BigDecimal.ZERO;
    BigDecimal totalDays = BigDecimal.ZERO;
    Map<UUID, MemberRoll> memberRoll = new LinkedHashMap<>();
    if (!teamResourceIds.isEmpty()) {
      List<DailyActivityResourceOutput> outputs =
          outputRepository.findByProjectIdAndOutputDateBetweenOrderByOutputDateDescIdAsc(
              projectId, from, to);
      for (DailyActivityResourceOutput o : outputs) {
        if (o.getResourceId() == null || !teamResourceIds.contains(o.getResourceId())) continue;
        if (o.getHoursWorked() != null) totalHours = totalHours.add(BigDecimal.valueOf(o.getHoursWorked()));
        if (o.getDaysWorked() != null) totalDays = totalDays.add(BigDecimal.valueOf(o.getDaysWorked()));
        memberRoll.computeIfAbsent(o.getResourceId(), k -> new MemberRoll(k)).add(o);
      }
    }

    Map<UUID, Resource> resourceById = new HashMap<>();
    if (!memberRoll.isEmpty()) {
      resourceRepository.findAllById(memberRoll.keySet()).forEach(r -> resourceById.put(r.getId(), r));
    }

    List<SupervisorPerformance.ActivityTopRollup> topActivities =
        activityRoll.values().stream()
            .sorted(Comparator.comparing((ActivityRoll r) -> r.qty).reversed())
            .limit(10)
            .map(
                r ->
                    new SupervisorPerformance.ActivityTopRollup(
                        r.activityId,
                        null,
                        r.activityName,
                        r.qty,
                        r.count,
                        null,
                        null))
            .toList();

    List<SupervisorPerformance.MemberRollup> topMembers =
        memberRoll.values().stream()
            .sorted(Comparator.comparing((MemberRoll m) -> m.days).reversed())
            .limit(15)
            .map(
                m -> {
                  Resource r = resourceById.get(m.resourceId);
                  return new SupervisorPerformance.MemberRollup(
                      m.resourceId,
                      r != null ? r.getCode() : null,
                      r != null ? r.getName() : null,
                      null,
                      m.qty,
                      BigDecimal.valueOf(m.hours),
                      BigDecimal.valueOf(m.days),
                      m.activityIds.size());
                })
            .toList();

    String matchSource =
        matchedById && matchedByName ? "mixed" : matchedById ? "activity_id" : matchedByName ? "name" : "none";

    SupervisorPerformance.DprRollup dprRollup =
        new SupervisorPerformance.DprRollup(
            dprCount,
            reportDates.size(),
            activitiesTouched.size(),
            totalQty,
            totalHours,
            totalDays,
            matchSource);
    return new DprAggregation(dprRollup, topActivities, topMembers);
  }

  private static class ActivityRoll {
    final UUID activityId;
    final String activityName;
    BigDecimal qty = BigDecimal.ZERO;
    int count = 0;

    ActivityRoll(UUID activityId, String activityName) {
      this.activityId = activityId;
      this.activityName = activityName;
    }

    void add(DailyProgressReport d) {
      count++;
      if (d.getQtyExecuted() != null) qty = qty.add(d.getQtyExecuted());
    }
  }

  private static class MemberRoll {
    final UUID resourceId;
    BigDecimal qty = BigDecimal.ZERO;
    double hours = 0;
    double days = 0;
    Set<UUID> activityIds = new HashSet<>();

    MemberRoll(UUID resourceId) {
      this.resourceId = resourceId;
    }

    void add(DailyActivityResourceOutput o) {
      if (o.getQtyExecuted() != null) qty = qty.add(o.getQtyExecuted());
      if (o.getHoursWorked() != null) hours += o.getHoursWorked();
      if (o.getDaysWorked() != null) days += o.getDaysWorked();
      if (o.getActivityId() != null) activityIds.add(o.getActivityId());
    }
  }
}
