package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates raw domain data into the six "standard" report shapes the frontend
 * reports page consumes. All queries tolerate missing tables/rows and return
 * empty-but-well-formed payloads rather than throwing — the reports endpoints
 * must stay green (HTTP 200) even on projects that don't have every data source
 * populated.
 *
 * <p>Key data sources:
 * <ul>
 *   <li>{@code public.monthly_evm_snapshots} — canonical EVM source (IC-PMS Phase E
 *       seeds 9 monthly rows for DMIC-PROG). {@code evm.evm_calculations} is an
 *       entity but never seeded, so we query it only as a secondary fallback.
 *   <li>{@code cost.activity_expenses} — budgeted/actual cost per activity,
 *       seeded for all projects in Phase C.
 *   <li>{@code cost.cash_flow_forecasts} — exists but not seeded; we derive the
 *       cash-flow report from {@code activity_expenses} when the table is empty.
 *   <li>{@code risk.risks} — seeded with RAG band (CRIMSON/RED/AMBER/GREEN/
 *       OPPORTUNITY); no {@code severity} column exists, so we band by RAG.
 *   <li>{@code resource.equipment_logs} — daily op/idle/breakdown hours per
 *       equipment, seeded 14 days ending 2026-04-14.
 *   <li>{@code contract.contracts} — seeded in Phase B with denormalised
 *       {@code spi/cpi/bg_expiry/physical_progress_ai} columns.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDataService {

  @PersistenceContext private EntityManager em;

  // =========================================================================
  // 1. Monthly Progress
  // =========================================================================
  @Transactional(readOnly = true)
  public MonthlyProgressData getMonthlyProgress(UUID projectId, String period) {
    String projectName = getProjectName(projectId);
    String projectCode = getProjectCode(projectId);

    YearMonth ym;
    try {
      ym = YearMonth.parse(period);
    } catch (Exception e) {
      ym = YearMonth.now();
    }
    LocalDate monthStart = ym.atDay(1);
    LocalDate monthEnd = ym.atEndOfMonth();

    int totalActivities = countActivitiesInWindow(projectId, monthStart, monthEnd);
    int completedActivities = countCompletedActivities(projectId, monthEnd);
    int inProgressActivities = countInProgressActivities(projectId);
    double overallPercentComplete = computeOverallPercentComplete(projectId, monthEnd);

    BigDecimal budgetAmount = getProjectBudget(projectId);
    BigDecimal actualCost = getActualCostInWindow(projectId, monthStart, monthEnd);
    BigDecimal forecastCost = getForecastCost(projectId);

    int totalMilestones = getTotalMilestones(projectId);
    int achievedMilestones = getAchievedMilestones(projectId, monthEnd);

    int openRisks = getOpenRisks(projectId);
    int highRisks = getHighRisks(projectId);

    List<MonthlyProgressData.ActivitySummaryRow> topDelayed =
        getTopDelayedActivities(projectId, 5, monthEnd);

    return new MonthlyProgressData(
        projectName, projectCode, period,
        totalActivities, completedActivities, inProgressActivities, overallPercentComplete,
        budgetAmount, actualCost, forecastCost,
        totalMilestones, achievedMilestones,
        openRisks, highRisks,
        topDelayed);
  }

  // =========================================================================
  // 2. EVM
  // =========================================================================
  @Transactional(readOnly = true)
  public EvmReportData getEvmReport(UUID projectId) {
    String projectName = getProjectName(projectId);

    // Pull the latest monthly EVM snapshot rolled up for the project. Phase E
    // seeder writes one row per (node, month); we sum across nodes for the
    // most recent month per project.
    EvmSnapshot latest = loadLatestEvmSnapshot(projectId);

    BigDecimal pv = latest.bcws;
    BigDecimal ev = latest.bcwp;
    BigDecimal ac = latest.acwp;
    BigDecimal bac = latest.bac.signum() > 0 ? latest.bac : getProjectBudget(projectId);

    double spi = pv.signum() > 0 ? ev.doubleValue() / pv.doubleValue() : 0.0;
    double cpi = ac.signum() > 0 ? ev.doubleValue() / ac.doubleValue() : 0.0;

    BigDecimal eac = cpi > 0 && bac != null
        ? bac.divide(BigDecimal.valueOf(cpi), 2, RoundingMode.HALF_UP)
        : (bac != null ? bac : BigDecimal.ZERO);

    BigDecimal etc = eac.subtract(ac);
    BigDecimal vac = (bac != null ? bac : BigDecimal.ZERO).subtract(eac);

    double tcpi = 0.0;
    if (bac != null && bac.signum() > 0 && eac.subtract(ac).signum() > 0) {
      tcpi = bac.subtract(ev).doubleValue() / eac.subtract(ac).doubleValue();
    }

    return new EvmReportData(
        projectName,
        pv, ev, ac,
        spi, cpi,
        eac, etc, vac,
        tcpi);
  }

  // =========================================================================
  // 3. Cash Flow
  // =========================================================================
  @Transactional(readOnly = true)
  public List<CashFlowEntry> getCashFlowReport(UUID projectId) {
    // Primary source: cost.cash_flow_forecasts. The table exists but isn't
    // seeded, so this usually returns empty — we then fall back to an
    // activity_expenses monthly rollup.
    List<CashFlowEntry> forecasted = queryCashFlowForecasts(projectId);
    if (!forecasted.isEmpty()) {
      return forecasted;
    }
    return deriveCashFlowFromActivities(projectId);
  }

  // =========================================================================
  // 4. Contract Status
  // =========================================================================
  @Transactional(readOnly = true)
  public ContractStatusData getContractStatus(UUID projectId) {
    String projectName = getProjectName(projectId);
    int totalContracts = getTotalContracts(projectId);
    int activeContracts = getActiveContracts(projectId);
    BigDecimal totalContractValue = getTotalContractValue(projectId);
    BigDecimal totalVoValue = getTotalVariationOrderValue(projectId);
    int pendingMilestones = getPendingMilestones(projectId);
    int achievedMilestones = getAchievedMilestones(projectId);
    List<ContractStatusData.ContractSummaryRow> contracts = getContractSummaries(projectId);

    return new ContractStatusData(
        projectName,
        totalContracts,
        activeContracts,
        totalContractValue,
        totalVoValue,
        pendingMilestones,
        achievedMilestones,
        contracts);
  }

  // =========================================================================
  // 5. Risk Register
  // =========================================================================
  @Transactional(readOnly = true)
  public RiskRegisterData getRiskRegister(UUID projectId) {
    String projectName = getProjectName(projectId);
    int totalRisks = getTotalRisks(projectId);
    // risk.risks has no `severity` column — it has `rag` (CRIMSON/RED/AMBER/GREEN/
    // OPPORTUNITY). Band accordingly: HIGH = CRIMSON+RED, MEDIUM = AMBER, LOW = GREEN.
    int highRisks = getRisksByRag(projectId, List.of("CRIMSON", "RED"));
    int mediumRisks = getRisksByRag(projectId, List.of("AMBER"));
    int lowRisks = getRisksByRag(projectId, List.of("GREEN"));
    Map<String, Integer> risksByCategory = getRisksByCategory(projectId);
    List<RiskRegisterData.RiskSummaryRow> topRisks = getTopRisks(projectId, 10);

    return new RiskRegisterData(
        projectName,
        totalRisks,
        highRisks, mediumRisks, lowRisks,
        risksByCategory,
        topRisks);
  }

  // =========================================================================
  // 6. Resource Utilisation
  // =========================================================================
  @Transactional(readOnly = true)
  public ResourceUtilizationData getResourceUtilization(UUID projectId) {
    String projectName = getProjectName(projectId);

    // Primary source: equipment_logs rolled up per resource. If the project
    // has no logged equipment, fall back to resource.resource_assignments
    // planned vs actual units (used by the legacy reports page).
    List<ResourceUtilizationData.ResourceUtilRow> resources =
        getResourceUtilizationFromEquipmentLogs(projectId);
    if (resources.isEmpty()) {
      resources = getResourceUtilizationFromAssignments(projectId);
    }

    int totalResources = resources.size();
    double avgUtilization = resources.isEmpty()
        ? 0.0
        : resources.stream()
            .mapToDouble(ResourceUtilizationData.ResourceUtilRow::utilPct)
            .average()
            .orElse(0.0);

    return new ResourceUtilizationData(
        projectName,
        totalResources,
        avgUtilization,
        resources);
  }

  // =========================================================================
  // Trend Analysis (kept as-is from prior implementation)
  // =========================================================================
  @Transactional(readOnly = true)
  public TrendAnalysisData getTrendAnalysis(UUID projectId, int months) {
    String projectName = getProjectName(projectId);
    List<TrendAnalysisData.PeriodMetric> periodMetrics = getPeriodMetrics(projectId, months);
    List<TrendAnalysisData.MilestoneStatusRow> milestoneStatus = getMilestoneStatus(projectId);
    Map<String, Integer> activityDistribution = getActivityDistribution(projectId);
    List<TrendAnalysisData.ResourceLoadingEntry> resourceLoadingTrend =
        getResourceLoadingTrend(projectId, months);

    return new TrendAnalysisData(
        projectName,
        periodMetrics,
        milestoneStatus,
        activityDistribution,
        resourceLoadingTrend);
  }

  // =========================================================================
  // EVM helpers
  // =========================================================================
  /** Compact value object for the latest EVM snapshot. */
  private record EvmSnapshot(
      BigDecimal bcws, BigDecimal bcwp, BigDecimal acwp, BigDecimal bac) {}

  private EvmSnapshot loadLatestEvmSnapshot(UUID projectId) {
    // Sum BCWS/BCWP/ACWP/BAC across all node rows for the most recent report_month.
    try {
      Object[] row = (Object[]) em.createNativeQuery(
              "SELECT " +
              "  COALESCE(SUM(bcws), 0), COALESCE(SUM(bcwp), 0), " +
              "  COALESCE(SUM(acwp), 0), COALESCE(SUM(bac), 0) " +
              "FROM public.monthly_evm_snapshots " +
              "WHERE project_id = ?1 " +
              "  AND report_month = (" +
              "    SELECT MAX(report_month) FROM public.monthly_evm_snapshots " +
              "    WHERE project_id = ?1)")
          .setParameter(1, projectId)
          .getSingleResult();
      return new EvmSnapshot(toBigDecimal(row[0]), toBigDecimal(row[1]),
          toBigDecimal(row[2]), toBigDecimal(row[3]));
    } catch (Exception e) {
      log.debug("No monthly_evm_snapshots for projectId={}: {}", projectId, e.getMessage());
      return new EvmSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
  }

  // =========================================================================
  // Cash-flow helpers
  // =========================================================================
  private List<CashFlowEntry> queryCashFlowForecasts(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT period, planned_amount, actual_amount, forecast_amount, " +
              "  cumulative_planned, cumulative_actual, cumulative_forecast " +
              "FROM cost.cash_flow_forecasts " +
              "WHERE project_id = ?1 ORDER BY period ASC")
          .setParameter(1, projectId)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new CashFlowEntry(
            c[0] != null ? c[0].toString() : "",
            toBigDecimal(c[1]), toBigDecimal(c[2]), toBigDecimal(c[3]),
            toBigDecimal(c[4]), toBigDecimal(c[5]), toBigDecimal(c[6]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.debug("cash_flow_forecasts query failed for projectId={}: {}",
          projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  /**
   * Derive cash-flow rows from {@code activity_expenses} when no forecast table
   * is populated. "Planned" per month = sum of budgeted_cost whose
   * planned_start_date falls in that month; "Actual" = sum of actual_cost whose
   * actual_start_date falls in that month.
   */
  private List<CashFlowEntry> deriveCashFlowFromActivities(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT " +
              "  to_char(COALESCE(planned_start_date, actual_start_date), 'YYYY-MM') AS period, " +
              "  COALESCE(SUM(CASE WHEN planned_start_date IS NOT NULL THEN budgeted_cost ELSE 0 END), 0) AS planned, " +
              "  COALESCE(SUM(CASE WHEN actual_start_date IS NOT NULL THEN actual_cost ELSE 0 END), 0) AS actual, " +
              "  COALESCE(SUM(at_completion_cost), 0) AS forecast " +
              "FROM cost.activity_expenses " +
              "WHERE project_id = ?1 " +
              "  AND COALESCE(planned_start_date, actual_start_date) IS NOT NULL " +
              // Postgres allows GROUP BY on an output column-alias at the top level; safer
              // to repeat the expression so this also works on other dialects.
              "GROUP BY to_char(COALESCE(planned_start_date, actual_start_date), 'YYYY-MM') " +
              "ORDER BY to_char(COALESCE(planned_start_date, actual_start_date), 'YYYY-MM') ASC")
          .setParameter(1, projectId)
          .getResultList();

      // Build running cumulative totals.
      BigDecimal cumPlanned = BigDecimal.ZERO;
      BigDecimal cumActual = BigDecimal.ZERO;
      BigDecimal cumForecast = BigDecimal.ZERO;
      List<CashFlowEntry> result = new ArrayList<>(rows.size());
      for (Object r : rows) {
        Object[] c = (Object[]) r;
        BigDecimal planned = toBigDecimal(c[1]);
        BigDecimal actual = toBigDecimal(c[2]);
        BigDecimal forecast = toBigDecimal(c[3]);
        cumPlanned = cumPlanned.add(planned);
        cumActual = cumActual.add(actual);
        cumForecast = cumForecast.add(forecast);
        result.add(new CashFlowEntry(
            c[0] != null ? c[0].toString() : "",
            planned, actual, forecast,
            cumPlanned, cumActual, cumForecast));
      }
      return result;
    } catch (Exception e) {
      log.warn("Failed to derive cash flow from activity_expenses for projectId={}: {}",
          projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  // =========================================================================
  // Resource helpers
  // =========================================================================
  private List<ResourceUtilizationData.ResourceUtilRow> getResourceUtilizationFromEquipmentLogs(
      UUID projectId) {
    try {
      // utilization = operatingHours / (operatingHours + idleHours + breakdownHours) * 100.
      // operatingHours doubles as "actualHours"; total of all three is the exposed time.
      // Each hour component can be NULL — coalesce per-row before summing so one NULL
      // doesn't null out the whole group.
      // r.resource_type became r.resource_type_id (FK) after the resource rewrite —
      // join through resource_types to surface the type code (EQUIPMENT / LABOR / MATERIAL).
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT r.code, r.name, COALESCE(rt.code, ''), " +
              "  SUM(COALESCE(l.operating_hours, 0) + COALESCE(l.idle_hours, 0) + COALESCE(l.breakdown_hours, 0)) AS planned, " +
              "  SUM(COALESCE(l.operating_hours, 0)) AS actual, " +
              "  CASE WHEN SUM(COALESCE(l.operating_hours, 0) + COALESCE(l.idle_hours, 0) + COALESCE(l.breakdown_hours, 0)) > 0 " +
              "    THEN ROUND((100.0 * SUM(COALESCE(l.operating_hours, 0)) / " +
              "      SUM(COALESCE(l.operating_hours, 0) + COALESCE(l.idle_hours, 0) + COALESCE(l.breakdown_hours, 0)))::numeric, 2) " +
              "    ELSE 0 END AS util_pct " +
              "FROM resource.equipment_logs l " +
              "JOIN resource.resources r ON l.resource_id = r.id " +
              "LEFT JOIN resource.resource_types rt ON rt.id = r.resource_type_id " +
              "WHERE l.project_id = ?1 " +
              "GROUP BY r.id, r.code, r.name, rt.code " +
              "ORDER BY util_pct DESC")
          .setParameter(1, projectId)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new ResourceUtilizationData.ResourceUtilRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            c[2] != null ? c[2].toString() : "",
            toDouble(c[3]), toDouble(c[4]), toDouble(c[5]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.debug("equipment_logs roll-up failed for projectId={}: {}", projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  private List<ResourceUtilizationData.ResourceUtilRow> getResourceUtilizationFromAssignments(
      UUID projectId) {
    try {
      // Union over legacy (resource_id chain) and role-only (role_id + variant chain). Type code
      // is derived from the variant FK when the legacy resource join misses.
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "WITH legacy AS (" +
              "  SELECT r.code AS code, r.name AS name, COALESCE(rt.code, '') AS type_code, " +
              "         COALESCE(SUM(a.planned_units), 0) AS planned_units, " +
              "         COALESCE(SUM(a.actual_units), 0)  AS actual_units " +
              "  FROM resource.resources r " +
              "  JOIN resource.resource_assignments a ON r.id = a.resource_id " +
              "  LEFT JOIN resource.resource_types rt ON rt.id = r.resource_type_id " +
              "  WHERE a.project_id = ?1 AND a.resource_id IS NOT NULL " +
              "  GROUP BY r.id, r.code, r.name, rt.code " +
              "), role_only AS (" +
              // nos × rate cost model: planned = Σ headcount (one row per assignment),
              // actual = Σ DPR nos (already aggregated in actual_units). Duration is excluded
              // so this matches the Workforce Deployment / Equipment Utilisation tiles on the
              // Insights tab.
              "  SELECT COALESCE(rr.code, rr.name) AS code, rr.name AS name, " +
              "         CASE WHEN a.manpower_role_rate_id  IS NOT NULL THEN 'MANPOWER' " +
              "              WHEN a.equipment_role_variant_id IS NOT NULL THEN 'EQUIPMENT' " +
              "              WHEN a.material_role_variant_id  IS NOT NULL THEN 'MATERIAL' " +
              "              ELSE '' END AS type_code, " +
              "         COALESCE(SUM(a.headcount), 0)    AS planned_units, " +
              "         COALESCE(SUM(a.actual_units), 0) AS actual_units " +
              "  FROM resource.resource_assignments a " +
              "  JOIN resource.resource_roles rr ON rr.id = a.role_id " +
              "  WHERE a.project_id = ?1 AND a.resource_id IS NULL AND a.role_id IS NOT NULL " +
              "  GROUP BY rr.id, rr.code, rr.name, " +
              "           a.manpower_role_rate_id, a.equipment_role_variant_id, a.material_role_variant_id " +
              ") " +
              "SELECT code, name, type_code, planned_units, actual_units, " +
              "  CASE WHEN planned_units > 0 " +
              "    THEN ROUND((100.0 * actual_units / planned_units)::numeric, 2) ELSE 0 END AS util_pct " +
              "FROM (SELECT * FROM legacy UNION ALL SELECT * FROM role_only) u " +
              "ORDER BY util_pct DESC")
          .setParameter(1, projectId)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new ResourceUtilizationData.ResourceUtilRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            c[2] != null ? c[2].toString() : "",
            toDouble(c[3]), toDouble(c[4]), toDouble(c[5]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.debug("resource_assignments roll-up failed for projectId={}: {}",
          projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  // =========================================================================
  // Activity + WBS helpers
  // =========================================================================
  private int countActivitiesInWindow(UUID projectId, LocalDate start, LocalDate end) {
    // Activities whose planned window overlaps the report month.
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
        "WHERE project_id = ?1 " +
        "  AND COALESCE(planned_start_date, actual_start_date) <= ?3 " +
        "  AND COALESCE(planned_finish_date, actual_finish_date, planned_start_date) >= ?2",
        projectId, start, end);
  }

  private int countCompletedActivities(UUID projectId, LocalDate asOf) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
        "WHERE project_id = ?1 AND status = 'COMPLETED' " +
        "  AND (actual_finish_date IS NULL OR actual_finish_date <= ?2)",
        projectId, asOf);
  }

  private int countInProgressActivities(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
        "WHERE project_id = ?1 AND status = 'IN_PROGRESS'",
        projectId);
  }

  private double computeOverallPercentComplete(UUID projectId, LocalDate asOf) {
    // Prefer the weighted roll-up from WBS summary_percent_complete × budget_crores.
    try {
      Object[] row = (Object[]) em.createNativeQuery(
              "SELECT " +
              "  COALESCE(SUM(summary_percent_complete * COALESCE(budget_crores, 1)), 0), " +
              "  COALESCE(SUM(COALESCE(budget_crores, 1)), 0) " +
              "FROM project.wbs_nodes " +
              "WHERE project_id = ?1 AND summary_percent_complete IS NOT NULL")
          .setParameter(1, projectId)
          .getSingleResult();
      BigDecimal weighted = toBigDecimal(row[0]);
      BigDecimal totalBudget = toBigDecimal(row[1]);
      if (totalBudget.signum() > 0) {
        return weighted.divide(totalBudget, 2, RoundingMode.HALF_UP).doubleValue();
      }
    } catch (Exception ignored) {
      // fall through
    }
    // Fall back to activity count ratio.
    int total = scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities WHERE project_id = ?1",
        projectId);
    int completed = countCompletedActivities(projectId, asOf);
    return total > 0 ? (completed * 100.0) / total : 0.0;
  }

  private List<MonthlyProgressData.ActivitySummaryRow> getTopDelayedActivities(
      UUID projectId, int limit, LocalDate asOf) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT code, name, status, " +
              "  COALESCE(total_float, 0) AS total_float, " +
              "  planned_finish_date " +
              "FROM activity.activities " +
              "WHERE project_id = ?1 " +
              "  AND actual_finish_date IS NULL " +
              "  AND planned_finish_date < ?2 " +
              "ORDER BY planned_finish_date ASC NULLS LAST " +
              "LIMIT ?3")
          .setParameter(1, projectId)
          .setParameter(2, asOf)
          .setParameter(3, limit)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        LocalDate plannedFinish = null;
        if (c[4] != null) {
          if (c[4] instanceof LocalDate ld) plannedFinish = ld;
          else if (c[4] instanceof java.sql.Date sd) plannedFinish = sd.toLocalDate();
          else try { plannedFinish = LocalDate.parse(c[4].toString()); } catch (Exception ignored) {}
        }
        return new MonthlyProgressData.ActivitySummaryRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            c[2] != null ? c[2].toString() : "",
            toDouble(c[3]),
            plannedFinish);
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.debug("top delayed query failed for projectId={}: {}", projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  // =========================================================================
  // Cost helpers
  // =========================================================================
  private BigDecimal getProjectBudget(UUID projectId) {
    // Project.current_budget is the authoritative BAC (approved, change-controlled).
    // Activity-expense rollup is the bottom-up forecast and is used only when no
    // project-level budget has been set yet.
    BigDecimal fromProject = scalarDecimal(
        "SELECT COALESCE(current_budget, 0) FROM project.projects WHERE id = ?1",
        projectId);
    if (fromProject.signum() > 0) return fromProject;

    return scalarDecimal(
        "SELECT COALESCE(SUM(budgeted_cost), 0) FROM cost.activity_expenses WHERE project_id = ?1",
        projectId);
  }

  private BigDecimal getActualCostInWindow(UUID projectId, LocalDate start, LocalDate end) {
    return scalarDecimal(
        "SELECT COALESCE(SUM(actual_cost), 0) FROM cost.activity_expenses " +
        "WHERE project_id = ?1 " +
        "  AND COALESCE(actual_start_date, planned_start_date) BETWEEN ?2 AND ?3",
        projectId, start, end);
  }

  private BigDecimal getForecastCost(UUID projectId) {
    return scalarDecimal(
        "SELECT COALESCE(SUM(at_completion_cost), 0) FROM cost.activity_expenses " +
        "WHERE project_id = ?1",
        projectId);
  }

  // =========================================================================
  // Milestone / Contract helpers
  // =========================================================================
  private int getTotalMilestones(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contract_milestones cm " +
        "JOIN contract.contracts c ON cm.contract_id = c.id WHERE c.project_id = ?1",
        projectId);
  }

  private int getAchievedMilestones(UUID projectId, LocalDate asOf) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contract_milestones cm " +
        "JOIN contract.contracts c ON cm.contract_id = c.id " +
        "WHERE c.project_id = ?1 AND cm.actual_date IS NOT NULL AND cm.actual_date <= ?2",
        projectId, asOf);
  }

  private int getAchievedMilestones(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contract_milestones cm " +
        "JOIN contract.contracts c ON cm.contract_id = c.id " +
        "WHERE c.project_id = ?1 AND cm.actual_date IS NOT NULL",
        projectId);
  }

  private int getPendingMilestones(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contract_milestones cm " +
        "JOIN contract.contracts c ON cm.contract_id = c.id " +
        "WHERE c.project_id = ?1 AND cm.actual_date IS NULL",
        projectId);
  }

  private int getTotalContracts(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contracts WHERE project_id = ?1",
        projectId);
  }

  private int getActiveContracts(UUID projectId) {
    // "Active" covers every ACTIVE_* band plus MOBILISATION and DELAYED (still
    // under execution). Terminal states: COMPLETED, TERMINATED, DLP, DRAFT, SUSPENDED.
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM contract.contracts " +
        "WHERE project_id = ?1 AND status IN ('ACTIVE', 'ACTIVE_AT_RISK', 'ACTIVE_DELAYED', 'MOBILISATION', 'DELAYED')",
        projectId);
  }

  private BigDecimal getTotalContractValue(UUID projectId) {
    return scalarDecimal(
        "SELECT COALESCE(SUM(contract_value), 0) FROM contract.contracts WHERE project_id = ?1",
        projectId);
  }

  private BigDecimal getTotalVariationOrderValue(UUID projectId) {
    return scalarDecimal(
        "SELECT COALESCE(SUM(vo.vo_value), 0) FROM contract.variation_orders vo " +
        "JOIN contract.contracts c ON vo.contract_id = c.id WHERE c.project_id = ?1",
        projectId);
  }

  private List<ContractStatusData.ContractSummaryRow> getContractSummaries(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT c.contract_number, c.contractor_name, " +
              "  COALESCE(c.contract_value, 0), COALESCE(c.status::text, ''), " +
              "  (SELECT COUNT(*) FROM contract.contract_milestones cm " +
              "   WHERE cm.contract_id = c.id AND cm.actual_date IS NULL) AS pending_count " +
              "FROM contract.contracts c " +
              "WHERE c.project_id = ?1 " +
              "ORDER BY c.contract_value DESC NULLS LAST " +
              "LIMIT 20")
          .setParameter(1, projectId)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new ContractStatusData.ContractSummaryRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            toBigDecimal(c[2]),
            c[3] != null ? c[3].toString() : "",
            toInt(c[4]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.warn("Failed to get contract summaries for projectId={}: {}", projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  // =========================================================================
  // Risk helpers
  // =========================================================================
  private int getOpenRisks(UUID projectId) {
    // "Open" == anything not in a terminal state. RiskStatus enum terminal states:
    // CLOSED, RESOLVED, ACCEPTED. Every OPEN_* sub-variant (and IDENTIFIED, ANALYZING,
    // MITIGATING, REALISED_PARTIALLY) counts as open.
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM risk.risks " +
        "WHERE project_id = ?1 AND status NOT IN ('CLOSED', 'RESOLVED', 'ACCEPTED')",
        projectId);
  }

  private int getHighRisks(UUID projectId) {
    // RAG-based: CRIMSON or RED counts as high.
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM risk.risks " +
        "WHERE project_id = ?1 AND rag IN ('CRIMSON', 'RED')",
        projectId);
  }

  private int getTotalRisks(UUID projectId) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM risk.risks WHERE project_id = ?1",
        projectId);
  }

  private int getRisksByRag(UUID projectId, List<String> rags) {
    if (rags.isEmpty()) return 0;
    StringBuilder sb = new StringBuilder(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM risk.risks WHERE project_id = ?1 AND rag IN (");
    for (int i = 0; i < rags.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append("?").append(i + 2);
    }
    sb.append(")");
    try {
      var query = em.createNativeQuery(sb.toString()).setParameter(1, projectId);
      for (int i = 0; i < rags.size(); i++) {
        query.setParameter(i + 2, rags.get(i));
      }
      Object result = query.getSingleResult();
      return result != null ? ((Number) result).intValue() : 0;
    } catch (Exception e) {
      log.debug("risks-by-rag query failed: {}", e.getMessage());
      return 0;
    }
  }

  private Map<String, Integer> getRisksByCategory(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT COALESCE(category::text, 'UNKNOWN'), CAST(COUNT(*) AS INTEGER) " +
              "FROM risk.risks WHERE project_id = ?1 GROUP BY category")
          .setParameter(1, projectId)
          .getResultList();

      Map<String, Integer> out = new HashMap<>();
      for (Object r : rows) {
        Object[] c = (Object[]) r;
        out.put(c[0] != null ? c[0].toString() : "UNKNOWN", toInt(c[1]));
      }
      return out;
    } catch (Exception e) {
      log.debug("risks-by-category query failed: {}", e.getMessage());
      return new HashMap<>();
    }
  }

  private List<RiskRegisterData.RiskSummaryRow> getTopRisks(UUID projectId, int limit) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT code, title, COALESCE(category::text, ''), " +
              "  COALESCE(probability::text, ''), " +
              "  COALESCE(rag::text, '') AS rag, " +
              "  COALESCE(risk_score, 0) " +
              "FROM risk.risks " +
              "WHERE project_id = ?1 " +
              "ORDER BY risk_score DESC NULLS LAST, created_at DESC " +
              "LIMIT ?2")
          .setParameter(1, projectId)
          .setParameter(2, limit)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new RiskRegisterData.RiskSummaryRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            c[2] != null ? c[2].toString() : "",
            c[3] != null ? c[3].toString() : "",
            c[4] != null ? c[4].toString() : "",
            toDouble(c[5]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.warn("Failed to get top risks for projectId={}: {}", projectId, e.getMessage());
      return new ArrayList<>();
    }
  }

  // =========================================================================
  // Trend helpers (preserved from prior implementation, column names fixed)
  // =========================================================================
  private List<TrendAnalysisData.PeriodMetric> getPeriodMetrics(UUID projectId, int months) {
    List<TrendAnalysisData.PeriodMetric> metrics = new ArrayList<>();
    LocalDate now = LocalDate.now();

    for (int i = months - 1; i >= 0; i--) {
      YearMonth ym = YearMonth.from(now.minusMonths(i));
      String period = ym.toString();
      LocalDate monthEnd = ym.atEndOfMonth();

      int total = countActivitiesAsOf(projectId, monthEnd);
      int completed = countCompletedAsOf(projectId, monthEnd);
      double pct = total > 0 ? (completed * 100.0) / total : 0.0;
      double spi = getSpiAsOf(projectId, monthEnd);
      double cpi = getCpiAsOf(projectId, monthEnd);

      metrics.add(new TrendAnalysisData.PeriodMetric(period, total, completed, pct, spi, cpi));
    }
    return metrics;
  }

  private int countActivitiesAsOf(UUID projectId, LocalDate asOf) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
        "WHERE project_id = ?1 AND planned_start_date <= ?2",
        projectId, asOf);
  }

  private int countCompletedAsOf(UUID projectId, LocalDate asOf) {
    return scalarInt(
        "SELECT CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
        "WHERE project_id = ?1 AND status = 'COMPLETED' AND actual_finish_date <= ?2",
        projectId, asOf);
  }

  private double getSpiAsOf(UUID projectId, LocalDate asOf) {
    // Read the pre-computed SPI from the most recent monthly snapshot on/before asOf.
    try {
      Object result = em.createNativeQuery(
              "SELECT COALESCE(AVG(spi), 0) FROM public.monthly_evm_snapshots " +
              "WHERE project_id = ?1 AND report_month <= ?2 " +
              "  AND report_month = (SELECT MAX(report_month) FROM public.monthly_evm_snapshots " +
              "                      WHERE project_id = ?1 AND report_month <= ?2)")
          .setParameter(1, projectId)
          .setParameter(2, asOf)
          .getSingleResult();
      return result != null ? ((Number) result).doubleValue() : 0.0;
    } catch (Exception e) {
      return 0.0;
    }
  }

  private double getCpiAsOf(UUID projectId, LocalDate asOf) {
    try {
      Object result = em.createNativeQuery(
              "SELECT COALESCE(AVG(cpi), 0) FROM public.monthly_evm_snapshots " +
              "WHERE project_id = ?1 AND report_month <= ?2 " +
              "  AND report_month = (SELECT MAX(report_month) FROM public.monthly_evm_snapshots " +
              "                      WHERE project_id = ?1 AND report_month <= ?2)")
          .setParameter(1, projectId)
          .setParameter(2, asOf)
          .getSingleResult();
      return result != null ? ((Number) result).doubleValue() : 0.0;
    } catch (Exception e) {
      return 0.0;
    }
  }

  private List<TrendAnalysisData.MilestoneStatusRow> getMilestoneStatus(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT cm.milestone_code, cm.milestone_name, cm.target_date, cm.actual_date, " +
              "  CASE WHEN cm.actual_date IS NOT NULL THEN 'ACHIEVED' " +
              "       WHEN cm.target_date < CURRENT_DATE THEN 'LATE' " +
              "       ELSE 'PENDING' END AS status, " +
              "  COALESCE(EXTRACT(DAY FROM (cm.actual_date - cm.target_date)), " +
              "           EXTRACT(DAY FROM (CURRENT_DATE - cm.target_date))) AS variance_days " +
              "FROM contract.contract_milestones cm " +
              "JOIN contract.contracts c ON cm.contract_id = c.id " +
              "WHERE c.project_id = ?1 " +
              "ORDER BY cm.target_date ASC")
          .setParameter(1, projectId)
          .getResultList();

      return rows.stream().map(r -> {
        Object[] c = (Object[]) r;
        return new TrendAnalysisData.MilestoneStatusRow(
            c[0] != null ? c[0].toString() : "",
            c[1] != null ? c[1].toString() : "",
            c[2] != null ? c[2].toString() : "",
            c[3] != null ? c[3].toString() : "",
            c[4] != null ? c[4].toString() : "PENDING",
            toInt(c[5]));
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.debug("milestone status query failed: {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private Map<String, Integer> getActivityDistribution(UUID projectId) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows = em.createNativeQuery(
              "SELECT status, CAST(COUNT(*) AS INTEGER) FROM activity.activities " +
              "WHERE project_id = ?1 GROUP BY status")
          .setParameter(1, projectId)
          .getResultList();

      Map<String, Integer> out = new HashMap<>();
      for (Object r : rows) {
        Object[] c = (Object[]) r;
        out.put(c[0] != null ? c[0].toString() : "UNKNOWN", toInt(c[1]));
      }
      return out;
    } catch (Exception e) {
      return new HashMap<>();
    }
  }

  private List<TrendAnalysisData.ResourceLoadingEntry> getResourceLoadingTrend(
      UUID projectId, int months) {
    List<TrendAnalysisData.ResourceLoadingEntry> entries = new ArrayList<>();
    LocalDate now = LocalDate.now();
    for (int i = months - 1; i >= 0; i--) {
      YearMonth ym = YearMonth.from(now.minusMonths(i));
      LocalDate monthStart = ym.atDay(1);
      LocalDate monthEnd = ym.atEndOfMonth();

      try {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(planned_units), 0), COALESCE(SUM(actual_units), 0), " +
                "  COUNT(DISTINCT resource_id) " +
                "FROM resource.resource_assignments " +
                "WHERE project_id = ?1 AND planned_start_date <= ?3 AND planned_finish_date >= ?2")
            .setParameter(1, projectId)
            .setParameter(2, monthStart)
            .setParameter(3, monthEnd)
            .getSingleResult();

        entries.add(new TrendAnalysisData.ResourceLoadingEntry(
            ym.toString(),
            toDouble(row[0]), toDouble(row[1]), toInt(row[2])));
      } catch (Exception e) {
        entries.add(new TrendAnalysisData.ResourceLoadingEntry(ym.toString(), 0.0, 0.0, 0));
      }
    }
    return entries;
  }

  // =========================================================================
  // Project helpers
  // =========================================================================
  private String getProjectName(UUID projectId) {
    try {
      Object result = em.createNativeQuery(
              "SELECT name FROM project.projects WHERE id = ?1")
          .setParameter(1, projectId)
          .getSingleResult();
      return result != null ? result.toString() : "Unknown Project";
    } catch (Exception e) {
      return "Project " + projectId;
    }
  }

  private String getProjectCode(UUID projectId) {
    try {
      Object result = em.createNativeQuery(
              "SELECT code FROM project.projects WHERE id = ?1")
          .setParameter(1, projectId)
          .getSingleResult();
      return result != null ? result.toString() : "N/A";
    } catch (Exception e) {
      return "N/A";
    }
  }

  // =========================================================================
  // Low-level scalar helpers
  // =========================================================================
  private int scalarInt(String sql, Object... params) {
    try {
      var query = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) {
        query.setParameter(i + 1, params[i]);
      }
      Object result = query.getSingleResult();
      return result != null ? ((Number) result).intValue() : 0;
    } catch (Exception e) {
      log.debug("scalarInt failed [{}]: {}", sql, e.getMessage());
      return 0;
    }
  }

  private BigDecimal scalarDecimal(String sql, Object... params) {
    try {
      var query = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) {
        query.setParameter(i + 1, params[i]);
      }
      Object result = query.getSingleResult();
      return toBigDecimal(result);
    } catch (Exception e) {
      log.debug("scalarDecimal failed [{}]: {}", sql, e.getMessage());
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    try {
      return new BigDecimal(o.toString());
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  private static double toDouble(Object o) {
    if (o == null) return 0.0;
    if (o instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(o.toString());
    } catch (Exception e) {
      return 0.0;
    }
  }

  private static int toInt(Object o) {
    if (o == null) return 0;
    if (o instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(o.toString());
    } catch (Exception e) {
      return 0;
    }
  }
}
