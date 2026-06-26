package com.bipros.reporting.portfolio;

import com.bipros.evm.application.service.EvmService;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.project.application.service.DprActualCostLookup;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.reporting.portfolio.dto.CashFlowOutlookPoint;
import com.bipros.reporting.portfolio.dto.CurrencyBudget;
import com.bipros.reporting.portfolio.dto.ComplianceRow;
import com.bipros.reporting.portfolio.dto.ContractorLeagueRow;
import com.bipros.reporting.portfolio.dto.CostOverrunRow;
import com.bipros.reporting.portfolio.dto.DelayedProjectRow;
import com.bipros.reporting.portfolio.dto.FundingUtilizationRow;
import com.bipros.reporting.portfolio.dto.PortfolioEvmRow;
import com.bipros.reporting.portfolio.dto.PortfolioScorecardDto;
import com.bipros.reporting.portfolio.dto.PortfolioScorecardDto.RagCounts;
import com.bipros.reporting.portfolio.dto.RiskHeatmapDto;
import com.bipros.reporting.portfolio.dto.ScheduleHealthRow;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioReportService {

  private static final BigDecimal CRORE = new BigDecimal("10000000");

  private final ProjectRepository projectRepository;
  private final EvmService evmService;
  private final DprActualCostLookup dprActualCostLookup;

  @PersistenceContext private EntityManager em;

  // ─────────────────────── helpers: budget factor + progress map ───────────────────────

  // factor: INR major-unit = crore (1e7), everything else = million (1e6)
  private static BigDecimal majorUnitFactor(String currency) {
    return currency == null || "INR".equalsIgnoreCase(currency)
        ? new BigDecimal("10000000") : new BigDecimal("1000000");
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, Double> avgPercentCompleteByProject() {
    Map<UUID, Double> map = new HashMap<>();
    List<Object[]> rows = em.createNativeQuery(
        "SELECT a.project_id, AVG(a.percent_complete) FROM activity.activities a "
            + "JOIN project.projects p ON p.id = a.project_id "
            + "WHERE p.archived_at IS NULL GROUP BY a.project_id").getResultList();
    for (Object[] r : rows) {
      if (r[0] == null) continue;
      UUID id = r[0] instanceof UUID u ? u : UUID.fromString(r[0].toString());
      map.put(id, r[1] == null ? 0.0 : ((Number) r[1]).doubleValue());
    }
    return map;
  }

  // ─────────────────────── O4 — EVM Rollup ───────────────────────

  @Transactional(readOnly = true)
  public List<PortfolioEvmRow> getEvmRollup() {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    var pctMap = avgPercentCompleteByProject();
    List<PortfolioEvmRow> rows = new ArrayList<>(projects.size());
    for (Project p : projects) {
      EvmCalculation e = evmService.computeEvmSnapshot(p.getId());
      BigDecimal pv = nullToZero(e.getPlannedValue());
      BigDecimal ev = nullToZero(e.getEarnedValue());
      BigDecimal ac = nullToZero(e.getActualCost());
      double cpi = e.getCostPerformanceIndex() != null ? e.getCostPerformanceIndex() : 0.0;
      double spi = e.getSchedulePerformanceIndex() != null ? e.getSchedulePerformanceIndex() : 0.0;
      BigDecimal cv = nullToZero(e.getCostVariance());
      BigDecimal sv = nullToZero(e.getScheduleVariance());
      BigDecimal eac = nullToZero(e.getEstimateAtCompletion());
      BigDecimal bac = nullToZero(e.getBudgetAtCompletion());
      Double pct = e.getPerformancePercentComplete() != null
          ? e.getPerformancePercentComplete() : pctMap.getOrDefault(p.getId(), 0.0);
      rows.add(new PortfolioEvmRow(
          p.getId(), p.getCode(), p.getName(), pv, ev, ac, cpi, spi, cv, sv, eac,
          bac, p.getBudgetCurrency(), pct));
    }
    return rows;
  }

  // ─────────────────────── O1 — Scorecard ───────────────────────

  @Transactional(readOnly = true)
  public PortfolioScorecardDto getScorecard() {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    Map<String, Long> byStatus = new LinkedHashMap<>();
    byStatus.put("PLANNED", 0L);
    byStatus.put("ACTIVE", 0L);
    byStatus.put("COMPLETED", 0L);
    byStatus.put("ON_HOLD", 0L);
    byStatus.put("CANCELLED", 0L);
    for (Project p : projects) {
      String s = p.getStatus() != null ? p.getStatus().name() : "UNKNOWN";
      byStatus.merge(s, 1L, Long::sum);
    }

    BigDecimal totalBudget = BigDecimal.ZERO;
    BigDecimal totalCommitted = BigDecimal.ZERO;
    BigDecimal totalSpent = BigDecimal.ZERO;

    // All three totals join through project.projects so archived (soft-deleted) projects'
    // satellite rows don't leak into portfolio rollups. Restoring a project re-includes them.
    totalBudget = queryScalarBigDecimal(
        "SELECT COALESCE(SUM(wn.budget_crores), 0) FROM project.wbs_nodes wn "
            + "JOIN project.projects p ON p.id = wn.project_id "
            + "WHERE p.archived_at IS NULL");
    totalCommitted = queryScalarBigDecimal(
        "SELECT COALESCE(SUM(c.contract_value), 0) / ?1 FROM contract.contracts c "
            + "JOIN project.projects p ON p.id = c.project_id "
            + "WHERE p.archived_at IS NULL",
        CRORE);
    totalSpent = queryScalarBigDecimal(
        "SELECT COALESCE(SUM(rb.net_amount), 0) / ?1 FROM cost.ra_bills rb "
            + "JOIN project.projects p ON p.id = rb.project_id "
            + "WHERE rb.status IN ('APPROVED','PAID','CERTIFIED') "
            + "  AND p.archived_at IS NULL",
        CRORE);

    long green = 0, amber = 0, red = 0, grey = 0;
    long activeWithCritical = 0;
    long openCriticalRisks = 0;

    for (Project p : projects) {
      EvmCalculation snap = evmService.computeEvmSnapshot(p.getId());
      BigDecimal snapBac = nullToZero(snap.getBudgetAtCompletion());
      BigDecimal snapEv = nullToZero(snap.getEarnedValue());
      BigDecimal snapAc = nullToZero(snap.getActualCost());
      String rag;
      if (snapBac.signum() == 0 && snapEv.signum() == 0 && snapAc.signum() == 0) {
        rag = "GREY";
      } else {
        rag = bandRag(snap.getCostPerformanceIndex(), snap.getSchedulePerformanceIndex());
      }
      switch (rag) {
        case "GREEN" -> green++;
        case "AMBER" -> amber++;
        case "RED" -> red++;
        case "GREY" -> grey++;
      }
    }

    activeWithCritical = queryScalarLong(
        "SELECT COUNT(DISTINCT p.id) FROM project.projects p "
            + "JOIN activity.activities a ON a.project_id = p.id "
            + "WHERE p.status = 'ACTIVE' AND a.is_critical = TRUE "
            + "  AND p.archived_at IS NULL");

    openCriticalRisks = queryScalarLong(
        "SELECT COUNT(*) FROM risk.risks r "
            + "JOIN project.projects p ON p.id = r.project_id "
            + "WHERE r.status NOT IN ('CLOSED','MITIGATED') "
            + "  AND (r.rag = 'RED' OR r.risk_score >= 15) "
            + "  AND p.archived_at IS NULL");

    @SuppressWarnings("unchecked")
    List<Object[]> curRows = em.createNativeQuery(
        "SELECT budget_currency, "
            + "SUM(COALESCE(current_budget,0) * (CASE WHEN UPPER(budget_currency)='INR' OR budget_currency IS NULL THEN 10000000 ELSE 1000000 END)) "
            + "FROM project.projects "
            + "WHERE archived_at IS NULL AND COALESCE(current_budget,0) > 0 "
            + "GROUP BY budget_currency").getResultList();
    List<CurrencyBudget> budgetByCurrency = new ArrayList<>();
    for (Object[] r : curRows) {
      String cur = r[0] != null ? r[0].toString() : "INR";
      BigDecimal raw = r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString());
      budgetByCurrency.add(new CurrencyBudget(cur, raw));
    }
    Double avgPct = queryScalarBigDecimal(
        "SELECT COALESCE(AVG(p_avg),0) FROM ("
            + "  SELECT AVG(a.percent_complete) AS p_avg FROM activity.activities a "
            + "  JOIN project.projects p ON p.id = a.project_id "
            + "  WHERE p.archived_at IS NULL GROUP BY a.project_id) t").doubleValue();

    @SuppressWarnings("unchecked")
    List<Object[]> spentRows = em.createNativeQuery(
        "SELECT cur, SUM(amt) FROM ("
      + "  SELECT p.budget_currency AS cur, COALESCE(SUM(t.line_cost),0) AS amt FROM ("
      + "    SELECT dpr_id, line_cost FROM project.dpr_manpower WHERE line_cost IS NOT NULL"
      + "    UNION ALL SELECT dpr_id, line_cost FROM project.dpr_equipment WHERE line_cost IS NOT NULL"
      + "    UNION ALL SELECT dpr_id, line_cost FROM project.dpr_material WHERE line_cost IS NOT NULL) t"
      + "  JOIN project.daily_progress_reports d ON d.id=t.dpr_id"
      + "  JOIN project.projects p ON p.id=d.project_id"
      + "  WHERE p.archived_at IS NULL AND d.approval_status='APPROVED' GROUP BY p.budget_currency"
      + "  UNION ALL"
      + "  SELECT p.budget_currency AS cur, COALESCE(SUM(c.quantity*COALESCE(a.rate_per_unit,0)),0) AS amt"
      + "  FROM project.dpr_sub_contractor c JOIN project.daily_progress_reports d ON d.id=c.dpr_id"
      + "  JOIN resource.activity_sub_contractor_assignments a ON a.id=c.activity_sub_contractor_assignment_id"
      + "  JOIN project.projects p ON p.id=d.project_id"
      + "  WHERE p.archived_at IS NULL AND d.approval_status='APPROVED' GROUP BY p.budget_currency"
      + ") x GROUP BY cur HAVING SUM(amt) > 0 ORDER BY cur").getResultList();
    List<CurrencyBudget> spentByCurrency = new ArrayList<>();
    for (Object[] r : spentRows) {
      spentByCurrency.add(new CurrencyBudget(r[0] != null ? r[0].toString() : "INR",
          r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString())));
    }

    @SuppressWarnings("unchecked")
    List<Object[]> commRows = em.createNativeQuery(
        "SELECT p.budget_currency, COALESCE(SUM(c.contract_value),0) FROM contract.contracts c "
      + "JOIN project.projects p ON p.id=c.project_id WHERE p.archived_at IS NULL "
      + "GROUP BY p.budget_currency HAVING COALESCE(SUM(c.contract_value),0) > 0 ORDER BY p.budget_currency").getResultList();
    List<CurrencyBudget> committedByCurrency = new ArrayList<>();
    for (Object[] r : commRows) {
      committedByCurrency.add(new CurrencyBudget(r[0] != null ? r[0].toString() : "INR",
          r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString())));
    }

    return new PortfolioScorecardDto(
        projects.size(),
        byStatus,
        scaleMoney(totalBudget),
        scaleMoney(totalCommitted),
        scaleMoney(totalSpent),
        new RagCounts(green, amber, red, grey),
        activeWithCritical,
        openCriticalRisks,
        budgetByCurrency,
        avgPct,
        spentByCurrency,
        committedByCurrency);
  }

  // ─────────────────────── O2 — Delayed projects ───────────────────────

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<DelayedProjectRow> getDelayedProjects(int limit) {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    List<DelayedProjectRow> rows = new ArrayList<>();
    for (Project p : projects) {
      LocalDate plannedFinish = p.getPlannedFinishDate();
      LocalDate forecastFinish = plannedFinish;
      long daysDelayed = 0;
      double spi = 0.0;

      try {
        Object result =
            em.createNativeQuery(
                    "SELECT MAX(actual_finish_date) FROM activity.activities "
                        + "WHERE project_id = ?1 AND actual_finish_date IS NOT NULL")
                .setParameter(1, p.getId())
                .getSingleResult();
        if (result != null && plannedFinish != null) {
          LocalDate maxActual = LocalDate.parse(result.toString());
          if (maxActual.isAfter(plannedFinish)) {
            forecastFinish = maxActual;
            daysDelayed = ChronoUnit.DAYS.between(plannedFinish, maxActual);
          }
        }
      } catch (Exception ignored) {
      }

      EvmCalculation latest = evmService.computeEvmSnapshot(p.getId());
      if (latest.getSchedulePerformanceIndex() != null) {
        spi = latest.getSchedulePerformanceIndex();
        if (spi > 0 && spi < 1.0 && plannedFinish != null && p.getPlannedStartDate() != null) {
          long planned = ChronoUnit.DAYS.between(p.getPlannedStartDate(), plannedFinish);
          long forecast = Math.round(planned / spi);
          long slip = forecast - planned;
          if (slip > daysDelayed) {
            daysDelayed = slip;
            forecastFinish = plannedFinish.plusDays(slip);
          }
        }
      }

      String rag = daysDelayed > 90 ? "RED" : daysDelayed > 30 ? "AMBER" : "GREEN";
      rows.add(
          new DelayedProjectRow(
              p.getId(), p.getCode(), p.getName(), plannedFinish, forecastFinish, daysDelayed, spi, rag));
    }
    rows.sort(Comparator.comparingLong(DelayedProjectRow::daysDelayed).reversed());
    return rows.stream().limit(limit).toList();
  }

  // ─────────────────────── O3 — Cost overrun ───────────────────────

  @Transactional(readOnly = true)
  public List<CostOverrunRow> getCostOverrunProjects(int limit) {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    List<CostOverrunRow> rows = new ArrayList<>();
    for (Project p : projects) {
      EvmCalculation e = evmService.computeEvmSnapshot(p.getId());
      BigDecimal bac = nullToZero(e.getBudgetAtCompletion());
      BigDecimal eac = nullToZero(e.getEstimateAtCompletion());
      double cpi = e.getCostPerformanceIndex() != null ? e.getCostPerformanceIndex() : 0.0;
      BigDecimal variance = eac.subtract(bac);
      rows.add(new CostOverrunRow(
          p.getId(), p.getCode(), p.getName(),
          scaleMoney(bac), scaleMoney(eac), scaleMoney(variance), cpi, p.getBudgetCurrency()));
    }
    // rank by |variance%| = |variance / bac|, currency-neutral across projects
    rows.sort(Comparator.comparing((CostOverrunRow r) ->
        (r.bacCrores() != null && r.bacCrores().signum() != 0)
          ? r.varianceCrores().abs().divide(r.bacCrores().abs(), 6, RoundingMode.HALF_UP)
          : BigDecimal.ZERO).reversed());
    return rows.stream().limit(limit).toList();
  }

  // ─────────────────────── O5 — Funding utilisation ───────────────────────

  @Transactional(readOnly = true)
  public List<FundingUtilizationRow> getFundingUtilization() {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    List<FundingUtilizationRow> rows = new ArrayList<>();
    for (Project p : projects) {
      // sanctioned: from project_funding table (may be empty → 0), RAW money, no /1e7
      BigDecimal sanctioned = queryScalarBigDecimal(
          "SELECT COALESCE(SUM(allocated_amount), 0) FROM cost.project_funding WHERE project_id = ?1",
          p.getId());
      // utilized: DPR ledger actual cost, RAW money (no /1e7)
      BigDecimal utilized = dprActualCostLookup.sumByProject(p.getId());
      // released: no releases table — honest 0 rather than faking released = sanctioned
      BigDecimal released = BigDecimal.ZERO;
      BigDecimal pendingTreasury = BigDecimal.ZERO;
      double releasePct = percent(released, sanctioned);
      double utilizationPct = percent(utilized, sanctioned);

      String status = "ON_TRACK";
      if (sanctioned.signum() == 0) {
        status = "NO_FUNDING_DATA";
      } else if (utilizationPct >= 95) {
        status = "EXHAUSTED";
      } else if (utilizationPct < 50) {
        status = "UNDER_UTILIZED";
      }
      String currency = p.getBudgetCurrency() != null ? p.getBudgetCurrency() : "INR";

      rows.add(
          new FundingUtilizationRow(
              p.getId(),
              p.getName(),
              scaleMoney(sanctioned),
              scaleMoney(released),
              scaleMoney(utilized),
              scaleMoney(pendingTreasury),
              releasePct,
              utilizationPct,
              status,
              currency));
    }
    return rows;
  }

  // ─────────────────────── O6 — Contractor league ───────────────────────

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<ContractorLeagueRow> getContractorLeague() {
    List<Object> rows =
        em.createNativeQuery(
                "SELECT c.contractor_code, MIN(c.contractor_name), "
                    + "       COUNT(DISTINCT c.project_id), "
                    + "       COALESCE(AVG(c.performance_score), 0), "
                    + "       COALESCE(AVG(c.spi), 0), "
                    + "       COALESCE(AVG(c.cpi), 0), "
                    + "       COALESCE(SUM(c.contract_value) / ?1, 0), "
                    + "       COALESCE(SUM(c.cumulative_ra_bills_crores), 0) "
                    + "FROM contract.contracts c "
                    + "JOIN project.projects p ON p.id = c.project_id "
                    + "WHERE c.contractor_code IS NOT NULL "
                    + "  AND p.archived_at IS NULL "
                    + "GROUP BY c.contractor_code "
                    + "ORDER BY 4 DESC")
            .setParameter(1, CRORE)
            .getResultList();

    List<ContractorLeagueRow> result = new ArrayList<>(rows.size());
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      result.add(
          new ContractorLeagueRow(
              cols[0] != null ? cols[0].toString() : "",
              cols[1] != null ? cols[1].toString() : "",
              cols[2] != null ? ((Number) cols[2]).longValue() : 0L,
              cols[3] != null ? ((Number) cols[3]).doubleValue() : 0.0,
              cols[4] != null ? ((Number) cols[4]).doubleValue() : 0.0,
              cols[5] != null ? ((Number) cols[5]).doubleValue() : 0.0,
              cols[6] != null ? new BigDecimal(cols[6].toString()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
              cols[7] != null ? new BigDecimal(cols[7].toString()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO));
    }
    return result;
  }

  // ─────────────────────── O7 — Risk heatmap ───────────────────────

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public RiskHeatmapDto getRiskHeatmap() {
    Map<String, Long> cellMap = new LinkedHashMap<>();
    List<Object> cellRows =
        em.createNativeQuery(
                "SELECT "
                    + "  CASE r.probability "
                    + "    WHEN 'VERY_LOW' THEN 1 WHEN 'LOW' THEN 2 WHEN 'MEDIUM' THEN 3 "
                    + "    WHEN 'HIGH' THEN 4 WHEN 'VERY_HIGH' THEN 5 ELSE 3 END AS p, "
                    + "  CASE r.impact "
                    + "    WHEN 'VERY_LOW' THEN 1 WHEN 'LOW' THEN 2 WHEN 'MEDIUM' THEN 3 "
                    + "    WHEN 'HIGH' THEN 4 WHEN 'VERY_HIGH' THEN 5 ELSE 3 END AS i, "
                    + "  COUNT(*) "
                    + "FROM risk.risks r "
                    + "JOIN project.projects pr ON pr.id = r.project_id "
                    + "WHERE r.status NOT IN ('CLOSED','MITIGATED') "
                    + "  AND pr.archived_at IS NULL "
                    + "GROUP BY p, i")
            .getResultList();
    List<RiskHeatmapDto.Cell> cells = new ArrayList<>();
    for (Object row : cellRows) {
      Object[] cols = (Object[]) row;
      int p = ((Number) cols[0]).intValue();
      int i = ((Number) cols[1]).intValue();
      long count = ((Number) cols[2]).longValue();
      cells.add(new RiskHeatmapDto.Cell(p, i, count));
    }

    List<Object> topRows =
        em.createNativeQuery(
                "SELECT r.id, r.project_id, p.code, r.code, r.title, r.probability, r.impact, "
                    + "       COALESCE(NULLIF(r.risk_score, 0), "
                    + "         (CASE r.probability WHEN 'VERY_LOW' THEN 1 WHEN 'LOW' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'HIGH' THEN 4 WHEN 'VERY_HIGH' THEN 5 ELSE 3 END) * "
                    + "         (CASE r.impact WHEN 'VERY_LOW' THEN 1 WHEN 'LOW' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'HIGH' THEN 4 WHEN 'VERY_HIGH' THEN 5 ELSE 3 END)) AS score, "
                    + "       COALESCE(r.rag, 'AMBER') "
                    + "FROM risk.risks r "
                    + "JOIN project.projects p ON p.id = r.project_id "
                    + "WHERE r.status NOT IN ('CLOSED','MITIGATED') "
                    + "  AND p.archived_at IS NULL "
                    + "ORDER BY score DESC NULLS LAST "
                    + "LIMIT 5")
            .getResultList();
    List<RiskHeatmapDto.TopRisk> top = new ArrayList<>();
    for (Object row : topRows) {
      Object[] cols = (Object[]) row;
      top.add(
          new RiskHeatmapDto.TopRisk(
              UUID.fromString(cols[0].toString()),
              cols[1] != null ? UUID.fromString(cols[1].toString()) : null,
              cols[2] != null ? cols[2].toString() : "",
              cols[3] != null ? cols[3].toString() : "",
              cols[4] != null ? cols[4].toString() : "",
              cols[5] != null ? cols[5].toString() : "",
              cols[6] != null ? cols[6].toString() : "",
              cols[7] != null ? ((Number) cols[7]).doubleValue() : 0.0,
              cols[8] != null ? cols[8].toString() : "AMBER"));
    }

    return new RiskHeatmapDto(cells, top);
  }

  // ─────────────────────── O8 — Cash flow outlook ───────────────────────

  @Transactional(readOnly = true)
  public List<CashFlowOutlookPoint> getCashFlowOutlook(int months) {
    // cost.cash_flow_forecasts is empty. Derive the series from the DPR ledger instead.
    // Strategy:
    //   ACTUAL months  (past + current): sum DPR line_cost per day, bucket into YYYY-MM, RAW.
    //   OUTLOOK months (future):         spread remaining BAC evenly from next month → project finish.
    // Mixed-currency: group by currency and emit the first/dominant series (OMR for demo).
    // If no DPR data exists at all, return empty (honest).

    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();

    // Per-currency: month → actual spend
    Map<String, Map<String, BigDecimal>> actualByCurrencyMonth = new LinkedHashMap<>();
    // Per-currency: month → planned outlook
    Map<String, Map<String, BigDecimal>> outlookByCurrencyMonth = new LinkedHashMap<>();

    YearMonth currentMonth = YearMonth.now();
    boolean anyDpr = false;

    for (Project p : projects) {
      String currency = p.getBudgetCurrency() != null ? p.getBudgetCurrency() : "INR";
      Map<LocalDate, BigDecimal> dailyCosts = dprActualCostLookup.sumByProjectGroupedByDate(p.getId());
      if (dailyCosts.isEmpty()) continue;
      anyDpr = true;

      // Bucket daily costs into YYYY-MM, accumulate per currency
      Map<String, BigDecimal> actMonths = actualByCurrencyMonth.computeIfAbsent(currency, k -> new LinkedHashMap<>());
      for (Map.Entry<LocalDate, BigDecimal> entry : dailyCosts.entrySet()) {
        String ym = YearMonth.from(entry.getKey()).toString();
        actMonths.merge(ym, entry.getValue(), BigDecimal::add);
      }

      // Outlook: remaining BAC spread evenly from next month → planned finish
      EvmCalculation snap = evmService.computeEvmSnapshot(p.getId());
      BigDecimal bac = nullToZero(snap.getBudgetAtCompletion());
      // Derive actualToDate from the already-fetched dailyCosts map (avoids a second DB round-trip)
      BigDecimal actualToDate = dailyCosts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal remaining = bac.subtract(actualToDate);
      if (remaining.signum() > 0 && p.getPlannedFinishDate() != null) {
        YearMonth outlookStart = currentMonth.plusMonths(1);
        YearMonth outlookEnd = YearMonth.from(p.getPlannedFinishDate());
        if (!outlookEnd.isBefore(outlookStart)) {
          long spreadMonths = outlookStart.until(outlookEnd, ChronoUnit.MONTHS) + 1;
          BigDecimal perMonth = remaining.divide(BigDecimal.valueOf(spreadMonths), 2, RoundingMode.HALF_UP);
          Map<String, BigDecimal> outlMonths = outlookByCurrencyMonth.computeIfAbsent(currency, k -> new LinkedHashMap<>());
          for (long i = 0; i < spreadMonths; i++) {
            String ym = outlookStart.plusMonths(i).toString();
            outlMonths.merge(ym, perMonth, BigDecimal::add);
          }
        }
      }
    }

    if (!anyDpr) return List.of();

    // Pick dominant currency (most months of actuals, tie-break: first seen)
    String dominantCurrency = actualByCurrencyMonth.entrySet().stream()
        .max(Comparator.comparingInt(e -> e.getValue().size()))
        .map(Map.Entry::getKey)
        .orElse("INR");

    Map<String, BigDecimal> actMonths = actualByCurrencyMonth.getOrDefault(dominantCurrency, Map.of());
    Map<String, BigDecimal> outlMonths = outlookByCurrencyMonth.getOrDefault(dominantCurrency, Map.of());

    // Build a time-ordered window: all months with actual data + forward outlook months
    Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();
    // Add past/current actuals (sorted)
    actMonths.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> byMonth.put(e.getKey(), new BigDecimal[]{e.getValue(), BigDecimal.ZERO}));
    // Add future outlook (sorted), not overwriting existing actual keys
    outlMonths.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> byMonth.computeIfAbsent(e.getKey(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[1] = e.getValue());

    List<CashFlowOutlookPoint> points = new ArrayList<>(byMonth.size());
    BigDecimal cumulative = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal[]> e : byMonth.entrySet()) {
      // [0] = actual spend (outflow), [1] = planned outlook (inflow proxy)
      BigDecimal actual = e.getValue()[0];
      BigDecimal planned = e.getValue()[1];
      BigDecimal net = actual.add(planned); // net spend for the month
      cumulative = cumulative.add(net);
      points.add(new CashFlowOutlookPoint(
          e.getKey(),
          scaleMoney(actual),   // plannedOutflowCrores → DPR actual (outflow)
          scaleMoney(planned),  // plannedInflowCrores  → forward BAC spread (outlook)
          scaleMoney(net),
          scaleMoney(cumulative),
          dominantCurrency));
    }
    // Bound to at most `months` points — keep the most-recent window.
    // Cumulative is computed over the full series (running total is preserved as-is for the
    // visible slice; the caller charts it as a running line so continuity is maintained).
    // months <= 0 → unbounded (return all).
    if (months > 0 && points.size() > months) {
      points = new ArrayList<>(points.subList(points.size() - months, points.size()));
    }
    return points;
  }

  // ─────────────────────── O9 — Compliance ───────────────────────

  @Transactional(readOnly = true)
  public List<ComplianceRow> getCompliance() {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    List<ComplianceRow> rows = new ArrayList<>();
    for (Project p : projects) {
      boolean pfms = queryScalarLong(
          "SELECT COUNT(*) FROM cost.project_funding WHERE project_id = ?1", p.getId()) > 0;
      // Stub checks: no integration tables seeded yet — conservative booleans.
      boolean gstn = queryScalarLong(
          "SELECT COUNT(*) FROM contract.contracts WHERE project_id = ?1", p.getId()) > 0;
      boolean gem = true;
      boolean cppp = queryScalarLong(
          "SELECT COUNT(*) FROM contract.tenders WHERE project_id = ?1", p.getId()) > 0;
      boolean parivesh = true;

      int total = 5;
      int pass =
          (pfms ? 1 : 0) + (gstn ? 1 : 0) + (gem ? 1 : 0) + (cppp ? 1 : 0) + (parivesh ? 1 : 0);
      double score = total > 0 ? (pass * 100.0) / total : 0.0;

      rows.add(
          new ComplianceRow(p.getId(), p.getCode(), p.getName(), pfms, gstn, gem, cppp, parivesh, score));
    }
    return rows;
  }

  // ─────────────────────── O10 — Schedule health ───────────────────────

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<ScheduleHealthRow> getScheduleHealth() {
    List<Project> projects = projectRepository.findAllByArchivedAtIsNull();
    List<ScheduleHealthRow> rows = new ArrayList<>();
    for (Project p : projects) {
      long missingLogic = queryScalarLong(
          "SELECT COUNT(a.id) FROM activity.activities a "
              + "LEFT JOIN activity.activity_relationships r "
              + "  ON (r.predecessor_activity_id = a.id OR r.successor_activity_id = a.id) "
              + "WHERE a.project_id = ?1 AND r.id IS NULL",
          p.getId());
      long leadRels = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activity_relationships r "
              + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
              + "WHERE a.project_id = ?1 AND r.lag < 0",
          p.getId());
      long lags = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activity_relationships r "
              + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
              + "WHERE a.project_id = ?1 AND r.lag > 0",
          p.getId());
      long totalRels = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activity_relationships r "
              + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
              + "WHERE a.project_id = ?1",
          p.getId());
      long fsRels = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activity_relationships r "
              + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
              + "WHERE a.project_id = ?1 AND r.relationship_type = 'FINISH_TO_START'",
          p.getId());
      double fsPct = totalRels > 0 ? (fsRels * 100.0) / totalRels : 100.0;

      long hardConstraints = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities "
              + "WHERE project_id = ?1 AND primary_constraint_type IN ('START_ON','FINISH_ON','AS_LATE_AS_POSSIBLE')",
          p.getId());
      long highFloat = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND total_float > 44",
          p.getId());
      long negFloat = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND total_float < 0",
          p.getId());
      long invalidDates = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities "
              + "WHERE project_id = ?1 AND planned_start_date IS NOT NULL AND planned_finish_date IS NOT NULL "
              + "  AND planned_start_date > planned_finish_date",
          p.getId());
      long resAllocIssues = queryScalarLong(
          "SELECT COUNT(a.id) FROM activity.activities a "
              + "LEFT JOIN resource.resource_assignments ra ON ra.activity_id = a.id "
              + "WHERE a.project_id = ?1 AND ra.id IS NULL",
          p.getId());
      long missedTasks = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities "
              + "WHERE project_id = ?1 AND planned_finish_date < CURRENT_DATE "
              + "  AND (percent_complete IS NULL OR percent_complete < 100)",
          p.getId());
      long cpLength = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND is_critical = TRUE",
          p.getId());
      boolean cpOk = cpLength > 0;

      double beiActual = 0.0;
      long completedByNow = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities "
              + "WHERE project_id = ?1 AND percent_complete >= 100",
          p.getId());
      long shouldHaveCompleted = queryScalarLong(
          "SELECT COUNT(*) FROM activity.activities "
              + "WHERE project_id = ?1 AND planned_finish_date <= CURRENT_DATE",
          p.getId());
      if (shouldHaveCompleted > 0) {
        beiActual = (completedByNow * 1.0) / shouldHaveCompleted;
      }
      double beiRequired = 0.95;

      int totalChecks = 14;
      int failed = 0;
      if (missingLogic > 0) failed++;
      if (leadRels > 0) failed++;
      if (lags > totalRels * 0.1) failed++;
      if (fsPct < 90) failed++;
      if (hardConstraints > 0) failed++;
      if (highFloat > 0) failed++;
      if (negFloat > 0) failed++;
      if (invalidDates > 0) failed++;
      if (resAllocIssues > 0) failed++;
      if (missedTasks > 0) failed++;
      if (!cpOk) failed++;
      if (beiActual < beiRequired) failed++;
      double healthPct = ((totalChecks - failed) * 100.0) / totalChecks;

      rows.add(
          new ScheduleHealthRow(
              p.getId(), p.getCode(), p.getName(),
              missingLogic, leadRels, lags, fsPct, hardConstraints, highFloat, negFloat,
              invalidDates, resAllocIssues, missedTasks, cpOk, cpLength,
              beiActual, beiRequired, healthPct));
    }
    return rows;
  }

  // ─────────────────────── helpers ───────────────────────

  private static BigDecimal nullToZero(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal scaleMoney(BigDecimal v) {
    if (v == null) return BigDecimal.ZERO;
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static double percent(BigDecimal num, BigDecimal den) {
    if (den == null || den.signum() == 0) return 0.0;
    return num.multiply(new BigDecimal("100"))
        .divide(den, 2, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private static String bandRag(Double cpi, Double spi) {
    if (cpi == null || spi == null || cpi == 0.0 || spi == 0.0) return "GREEN";
    if (cpi >= 0.95 && spi >= 0.95) return "GREEN";
    if ((cpi >= 0.85 && cpi < 0.95) || (spi >= 0.85 && spi < 0.95)) return "AMBER";
    return "RED";
  }

  private BigDecimal queryScalarBigDecimal(String sql, Object... params) {
    try {
      var q = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
      Object r = q.getSingleResult();
      return r != null ? new BigDecimal(r.toString()) : BigDecimal.ZERO;
    } catch (Exception e) {
      log.debug("queryScalarBigDecimal failed: {}", e.getMessage());
      return BigDecimal.ZERO;
    }
  }

  private long queryScalarLong(String sql, Object... params) {
    try {
      var q = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
      Object r = q.getSingleResult();
      return r != null ? ((Number) r).longValue() : 0L;
    } catch (Exception e) {
      log.debug("queryScalarLong failed: {}", e.getMessage());
      return 0L;
    }
  }
}
