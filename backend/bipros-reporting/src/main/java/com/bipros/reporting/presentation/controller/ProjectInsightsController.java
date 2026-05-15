package com.bipros.reporting.presentation.controller;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.dto.ApiResponse;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.reporting.presentation.dto.ActivityStatusRow;
import com.bipros.reporting.presentation.dto.CostVarianceRow;
import com.bipros.reporting.presentation.dto.MilestoneRow;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.CpppBlock;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.GemBlock;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.GstnBlock;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.HseBlock;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.PariveshBlock;
import com.bipros.reporting.presentation.dto.ProjectComplianceDto.PfmsBlock;
import com.bipros.reporting.presentation.dto.ProjectStatusSnapshotDto;
import com.bipros.reporting.presentation.dto.RaBillSummaryDto;
import com.bipros.reporting.presentation.dto.VariationOrderRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Project-scoped read-only insights that back the project-detail dashboards
 * (Status / Compliance / Milestones / WBS / Cost Variance / Activity list / etc.).
 * Uses {@code /v1/projects/{id}/*} (no /reports prefix) because the UI treats these as
 * live project data, not on-demand report generation.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
@PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
@RequiredArgsConstructor
@Slf4j
public class ProjectInsightsController {

  private static final BigDecimal CRORE = new BigDecimal("10000000");

  private final ProjectRepository projectRepository;
  private final WbsNodeRepository wbsNodeRepository;
  private final EvmCalculationRepository evmCalculationRepository;
  private final ActivityRepository activityRepository;

  @PersistenceContext private EntityManager em;

  // ─────────────── J5 — WBS progress ───────────────

  public record WbsProgressRow(
      String wbsCode,
      String wbsName,
      Integer level,
      double plannedPct,
      double actualPct,
      double variancePct) {}

  /**
   * WBS progress is a derived view, not a stored value. We deliberately do NOT read
   * {@code wbs_nodes.summary_percent_complete} — that column has no writer in the system
   * today and would always return 0%. Instead we walk the WBS tree bottom-up and weight
   * each descendant activity by its {@code original_duration} (fallback weight = 1):
   *
   * <pre>
   *   actualPct(node)  = Σ (activity.percent_complete × duration) ÷ Σ duration
   *   plannedPct(node) = Σ (linearPlannedPct(today, planned_start, planned_finish) × duration) ÷ Σ duration
   * </pre>
   *
   * Activities with null planned dates are excluded from the planned-pct numerator only;
   * they still contribute their actual %. This matches the standard "duration-weighted
   * earned schedule" approach used by Primavera / MS Project rollups.
   */
  @GetMapping("/wbs-progress")
  public ApiResponse<List<WbsProgressRow>> getWbsProgress(@PathVariable UUID projectId) {
    List<WbsNode> nodes = wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId);
    if (nodes.isEmpty()) return ApiResponse.ok(List.of());

    List<Activity> activities = activityRepository.findByProjectId(projectId);
    LocalDate today = LocalDate.now();

    // Build node-id → direct-children map and node-id → activities-directly-under map.
    Map<UUID, List<WbsNode>> childrenByParent = new HashMap<>();
    for (WbsNode n : nodes) {
      if (n.getParentId() != null) {
        childrenByParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
      }
    }
    Map<UUID, List<Activity>> activitiesByWbs = new HashMap<>();
    for (Activity a : activities) {
      if (a.getWbsNodeId() == null) continue;
      activitiesByWbs.computeIfAbsent(a.getWbsNodeId(), k -> new ArrayList<>()).add(a);
    }

    // Weighted aggregator returns [actualWeightedSum, plannedWeightedSum, weightSum, plannedWeightSum].
    Map<UUID, double[]> rollup = new HashMap<>();
    for (WbsNode n : nodes) rollup.put(n.getId(), aggregate(n, childrenByParent, activitiesByWbs, today));

    List<WbsProgressRow> result = new ArrayList<>(nodes.size());
    for (WbsNode n : nodes) {
      double[] agg = rollup.get(n.getId());
      double actual = agg[2] > 0d ? agg[0] / agg[2] : 0d;
      double planned = agg[3] > 0d ? agg[1] / agg[3] : 0d;
      result.add(new WbsProgressRow(
          n.getCode(), n.getName(), n.getWbsLevel(),
          round2(planned), round2(actual), round2(actual - planned)));
    }
    return ApiResponse.ok(result);
  }

  private double[] aggregate(
      WbsNode node,
      Map<UUID, List<WbsNode>> childrenByParent,
      Map<UUID, List<Activity>> activitiesByWbs,
      LocalDate today) {
    double actualSum = 0d;
    double plannedSum = 0d;
    double weight = 0d;
    double plannedWeight = 0d;

    // Activities directly under this node
    for (Activity a : activitiesByWbs.getOrDefault(node.getId(), List.of())) {
      double w = a.getOriginalDuration() != null && a.getOriginalDuration() > 0
          ? a.getOriginalDuration()
          : 1d;
      double pc = a.getPercentComplete() != null ? a.getPercentComplete() : 0d;
      actualSum += pc * w;
      weight += w;
      if (a.getPlannedStartDate() != null && a.getPlannedFinishDate() != null) {
        plannedSum += computePlannedPct(today, a.getPlannedStartDate(), a.getPlannedFinishDate()) * w;
        plannedWeight += w;
      }
    }

    // Recurse into child WBS nodes
    for (WbsNode child : childrenByParent.getOrDefault(node.getId(), List.of())) {
      double[] childAgg = aggregate(child, childrenByParent, activitiesByWbs, today);
      actualSum += childAgg[0];
      plannedSum += childAgg[1];
      weight += childAgg[2];
      plannedWeight += childAgg[3];
    }

    return new double[] {actualSum, plannedSum, weight, plannedWeight};
  }

  private static double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  // ─────────────── J1 — Status snapshot ───────────────

  @GetMapping("/status-snapshot")
  public ApiResponse<ProjectStatusSnapshotDto> getStatusSnapshot(@PathVariable UUID projectId) {
    Project p = projectRepository.findById(projectId).orElse(null);
    if (p == null) return ApiResponse.ok(null);

    Optional<EvmCalculation> latest =
        evmCalculationRepository.findTopByProjectIdOrderByDataDateDesc(projectId);

    double cpi = latest.map(e -> e.getCostPerformanceIndex() != null ? e.getCostPerformanceIndex() : 0.0).orElse(0.0);
    double spi = latest.map(e -> e.getSchedulePerformanceIndex() != null ? e.getSchedulePerformanceIndex() : 0.0).orElse(0.0);
    BigDecimal bac = latest.map(e -> nullToZero(e.getBudgetAtCompletion())).orElse(BigDecimal.ZERO);
    BigDecimal eac = latest.map(e -> nullToZero(e.getEstimateAtCompletion())).orElse(BigDecimal.ZERO);

    String scheduleRag = bandOne(spi);
    String costRag = bandOne(cpi);
    String scopeRag = "GREEN"; // no scope-change feed yet
    long activeRisks = queryScalarLong(
        "SELECT COUNT(*) FROM risk.risks WHERE project_id = ?1 AND status NOT IN ('CLOSED','MITIGATED')",
        projectId);
    long criticalRisks = queryScalarLong(
        "SELECT COUNT(*) FROM risk.risks WHERE project_id = ?1 "
            + "AND status NOT IN ('CLOSED','MITIGATED') AND (rag = 'RED' OR risk_score >= 15)",
        projectId);
    String riskRag = criticalRisks > 0 ? "RED" : activeRisks > 0 ? "AMBER" : "GREEN";
    String hseRag = "GREEN"; // no HSE feed yet

    LocalDate today = LocalDate.now();
    double physicalPct = queryScalarDouble(
        "SELECT COALESCE(AVG(percent_complete), 0) FROM activity.activities WHERE project_id = ?1",
        projectId);
    double plannedPct = 0.0;
    if (p.getPlannedStartDate() != null && p.getPlannedFinishDate() != null) {
      plannedPct = computePlannedPct(today, p.getPlannedStartDate(), p.getPlannedFinishDate());
    }

    List<String> topIssues = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<Object> issueRows =
        em.createNativeQuery(
                "SELECT code || ' — ' || title FROM risk.risks "
                    + "WHERE project_id = ?1 AND status NOT IN ('CLOSED','MITIGATED') "
                    + "ORDER BY COALESCE(risk_score, 0) DESC LIMIT 5")
            .setParameter(1, projectId)
            .getResultList();
    for (Object o : issueRows) topIssues.add(o.toString());

    String nextMilestoneName = null;
    LocalDate nextMilestoneDate = null;
    try {
      Object[] row = (Object[]) em.createNativeQuery(
              "SELECT name, planned_finish_date FROM activity.activities "
                  + "WHERE project_id = ?1 "
                  + "  AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE') "
                  + "  AND (actual_finish_date IS NULL) "
                  + "  AND planned_finish_date >= CURRENT_DATE "
                  + "ORDER BY planned_finish_date LIMIT 1")
          .setParameter(1, projectId)
          .getSingleResult();
      if (row != null) {
        nextMilestoneName = row[0] != null ? row[0].toString() : null;
        if (row[1] != null) nextMilestoneDate = LocalDate.parse(row[1].toString());
      }
    } catch (Exception ignored) {
    }

    return ApiResponse.ok(
        new ProjectStatusSnapshotDto(
            p.getId(), p.getCode(), p.getName(),
            p.getStatus() != null ? p.getStatus().name() : null,
            p.getPlannedStartDate(), p.getPlannedFinishDate(),
            scheduleRag, costRag, scopeRag, riskRag, hseRag,
            topIssues, nextMilestoneName, nextMilestoneDate,
            cpi, spi, physicalPct, plannedPct, activeRisks, 0L,
            toCrores(bac), toCrores(eac),
            p.getUpdatedAt()));
  }

  // ─────────────── J4 — Cost variance per WBS ───────────────

  @GetMapping("/cost-variance")
  @SuppressWarnings("unchecked")
  public ApiResponse<List<CostVarianceRow>> getCostVariance(@PathVariable UUID projectId) {
    List<WbsNode> nodes = wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId);
    // Pre-fetch cost aggregates per WBS via a single grouped query.
    // cost.activity_expenses has no wbs_node_id — roll up via the activity → wbs_node_id join.
    Map<UUID, BigDecimal[]> costByWbs = new HashMap<>();
    try {
      List<Object> rows = em.createNativeQuery(
              "SELECT a.wbs_node_id, "
                  + "       COALESCE(SUM(e.budgeted_cost), 0), "
                  + "       COALESCE(SUM(e.actual_cost), 0), "
                  + "       COALESCE(SUM(e.at_completion_cost), 0) "
                  + "FROM cost.activity_expenses e "
                  + "JOIN activity.activities a ON a.id = e.activity_id "
                  + "WHERE e.project_id = ?1 "
                  + "GROUP BY a.wbs_node_id")
          .setParameter(1, projectId)
          .getResultList();
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        costByWbs.put(
            UUID.fromString(cols[0].toString()),
            new BigDecimal[] {
                new BigDecimal(cols[1].toString()),
                new BigDecimal(cols[2].toString()),
                new BigDecimal(cols[3].toString())
            });
      }
    } catch (Exception e) {
      log.debug("cost-variance aggregate failed: {}", e.getMessage());
    }

    List<CostVarianceRow> result = new ArrayList<>();
    for (WbsNode n : nodes) {
      BigDecimal budget = n.getBudgetCrores() != null ? n.getBudgetCrores() : BigDecimal.ZERO;
      BigDecimal committed = BigDecimal.ZERO;
      BigDecimal actualRupees = BigDecimal.ZERO;
      BigDecimal forecastRupees = BigDecimal.ZERO;
      BigDecimal[] agg = costByWbs.get(n.getId());
      if (agg != null) {
        committed = agg[0];
        actualRupees = agg[1];
        forecastRupees = agg[2];
      }
      BigDecimal actualCrores = toCrores(actualRupees);
      BigDecimal committedCrores = toCrores(committed);
      BigDecimal forecastCrores = toCrores(forecastRupees);
      BigDecimal varianceCrores = forecastCrores.subtract(budget);
      double variancePct = budget.signum() != 0
          ? varianceCrores.multiply(new BigDecimal("100"))
              .divide(budget, 2, RoundingMode.HALF_UP)
              .doubleValue()
          : 0.0;
      result.add(
          new CostVarianceRow(
              n.getId(), n.getCode(), n.getName(), n.getWbsLevel(),
              scaleMoney(budget), scaleMoney(committedCrores), scaleMoney(actualCrores),
              scaleMoney(forecastCrores), scaleMoney(varianceCrores), variancePct));
    }
    return ApiResponse.ok(result);
  }

  // ─────────────── J6 — RA Bill summary ───────────────

  @GetMapping("/ra-bill-summary")
  @SuppressWarnings("unchecked")
  public ApiResponse<RaBillSummaryDto> getRaBillSummary(@PathVariable UUID projectId) {
    BigDecimal[] byStatus = new BigDecimal[6]; // submitted, pending, approved, paid, rejected, retention
    for (int i = 0; i < byStatus.length; i++) byStatus[i] = BigDecimal.ZERO;

    try {
      List<Object> rows = em.createNativeQuery(
              "SELECT status, COALESCE(SUM(net_amount), 0), COALESCE(SUM(retention_5_pct), 0) "
                  + "FROM cost.ra_bills WHERE project_id = ?1 GROUP BY status")
          .setParameter(1, projectId)
          .getResultList();
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        String status = cols[0] != null ? cols[0].toString() : "";
        BigDecimal amt = new BigDecimal(cols[1].toString());
        BigDecimal ret = new BigDecimal(cols[2].toString());
        switch (status) {
          case "SUBMITTED" -> {
            byStatus[0] = byStatus[0].add(amt);
            byStatus[1] = byStatus[1].add(amt);
          }
          case "CERTIFIED" -> {
            byStatus[0] = byStatus[0].add(amt);
            byStatus[2] = byStatus[2].add(amt);
          }
          case "APPROVED" -> {
            byStatus[0] = byStatus[0].add(amt);
            byStatus[2] = byStatus[2].add(amt);
          }
          case "PAID" -> {
            byStatus[0] = byStatus[0].add(amt);
            byStatus[3] = byStatus[3].add(amt);
          }
          case "REJECTED" -> byStatus[4] = byStatus[4].add(amt);
          default -> byStatus[0] = byStatus[0].add(amt);
        }
        byStatus[5] = byStatus[5].add(ret);
      }
    } catch (Exception e) {
      log.debug("ra-bill-summary aggregate failed: {}", e.getMessage());
    }

    List<RaBillSummaryDto.BillRow> bills = new ArrayList<>();
    try {
      List<Object> rows = em.createNativeQuery(
              "SELECT id, bill_number, bill_period_from, bill_period_to, status, "
                  + "       gross_amount, net_amount, submitted_date, approved_date, paid_date "
                  + "FROM cost.ra_bills WHERE project_id = ?1 "
                  + "ORDER BY submitted_date DESC NULLS LAST LIMIT 50")
          .setParameter(1, projectId)
          .getResultList();
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        bills.add(new RaBillSummaryDto.BillRow(
            UUID.fromString(cols[0].toString()),
            cols[1] != null ? cols[1].toString() : "",
            cols[2] != null ? LocalDate.parse(cols[2].toString()) : null,
            cols[3] != null ? LocalDate.parse(cols[3].toString()) : null,
            cols[4] != null ? cols[4].toString() : "",
            cols[5] != null ? new BigDecimal(cols[5].toString()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
            cols[6] != null ? new BigDecimal(cols[6].toString()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
            cols[7] != null ? LocalDate.parse(cols[7].toString()) : null,
            cols[8] != null ? LocalDate.parse(cols[8].toString()) : null,
            cols[9] != null ? LocalDate.parse(cols[9].toString()) : null));
      }
    } catch (Exception e) {
      log.debug("ra-bill-summary list failed: {}", e.getMessage());
    }

    return ApiResponse.ok(new RaBillSummaryDto(
        toCrores(byStatus[0]), toCrores(byStatus[1]), toCrores(byStatus[2]),
        toCrores(byStatus[3]), toCrores(byStatus[4]), toCrores(byStatus[5]),
        BigDecimal.ZERO, bills));
  }

  // ─────────────── J8 — Activity status list ───────────────

  @GetMapping("/activity-status")
  @SuppressWarnings("unchecked")
  public ApiResponse<List<ActivityStatusRow>> getActivityStatus(
      @PathVariable UUID projectId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean criticalOnly,
      @RequestParam(defaultValue = "200") int limit) {
    StringBuilder sql = new StringBuilder(
        "SELECT a.id, a.code, a.name, w.code, w.name, a.status, a.activity_type, "
            + "a.planned_start_date, a.planned_finish_date, a.actual_start_date, a.actual_finish_date, "
            + "a.early_start_date, a.early_finish_date, a.total_float, a.free_float, a.is_critical, "
            + "COALESCE(a.percent_complete, 0), a.remaining_duration "
            + "FROM activity.activities a "
            + "LEFT JOIN project.wbs_nodes w ON w.id = a.wbs_node_id "
            + "WHERE a.project_id = ?1 ");
    if (status != null && !status.isBlank()) sql.append("AND a.status = ?2 ");
    if (criticalOnly) sql.append("AND a.is_critical = TRUE ");
    sql.append("ORDER BY a.code LIMIT ").append(Math.min(Math.max(limit, 1), 1000));

    var q = em.createNativeQuery(sql.toString()).setParameter(1, projectId);
    if (status != null && !status.isBlank()) q.setParameter(2, status);
    List<Object> rows;
    try {
      rows = q.getResultList();
    } catch (Exception e) {
      log.debug("activity-status query failed: {}", e.getMessage());
      return ApiResponse.ok(List.of());
    }

    LocalDate today = LocalDate.now();
    List<ActivityStatusRow> result = new ArrayList<>(rows.size());
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      LocalDate plannedFinish = cols[8] != null ? LocalDate.parse(cols[8].toString()) : null;
      LocalDate actualFinish = cols[10] != null ? LocalDate.parse(cols[10].toString()) : null;
      double pct = cols[16] != null ? ((Number) cols[16]).doubleValue() : 0.0;
      long daysDelay = 0;
      if (plannedFinish != null && pct < 100 && today.isAfter(plannedFinish)) {
        daysDelay = ChronoUnit.DAYS.between(plannedFinish, today);
      } else if (plannedFinish != null && actualFinish != null && actualFinish.isAfter(plannedFinish)) {
        daysDelay = ChronoUnit.DAYS.between(plannedFinish, actualFinish);
      }
      long daysRemaining = 0;
      if (plannedFinish != null && pct < 100) {
        daysRemaining = Math.max(0, ChronoUnit.DAYS.between(today, plannedFinish));
      }
      result.add(new ActivityStatusRow(
          UUID.fromString(cols[0].toString()),
          cols[1] != null ? cols[1].toString() : "",
          cols[2] != null ? cols[2].toString() : "",
          cols[3] != null ? cols[3].toString() : "",
          cols[4] != null ? cols[4].toString() : "",
          cols[5] != null ? cols[5].toString() : "",
          cols[6] != null ? cols[6].toString() : "",
          cols[7] != null ? LocalDate.parse(cols[7].toString()) : null,
          plannedFinish,
          cols[9] != null ? LocalDate.parse(cols[9].toString()) : null,
          actualFinish,
          cols[11] != null ? LocalDate.parse(cols[11].toString()) : null,
          cols[12] != null ? LocalDate.parse(cols[12].toString()) : null,
          cols[13] != null ? ((Number) cols[13]).doubleValue() : null,
          cols[14] != null ? ((Number) cols[14]).doubleValue() : null,
          cols[15] != null && (Boolean) cols[15],
          pct,
          daysDelay,
          daysRemaining));
    }
    return ApiResponse.ok(result);
  }

  // ─────────────── J10 — Project-level compliance ───────────────

  @GetMapping("/compliance")
  public ApiResponse<ProjectComplianceDto> getCompliance(@PathVariable UUID projectId) {
    long fundingRows = queryScalarLong(
        "SELECT COUNT(*) FROM cost.project_funding WHERE project_id = ?1", projectId);
    boolean sanctionOk = fundingRows > 0;

    long contractorCount = queryScalarLong(
        "SELECT COUNT(DISTINCT contractor_code) FROM contract.contracts WHERE project_id = ?1",
        projectId);

    long linkedOrders = queryScalarLong(
        "SELECT COUNT(*) FROM contract.contracts WHERE project_id = ?1", projectId);
    BigDecimal gemValueCrores = toCrores(
        queryScalarBigDecimal(
            "SELECT COALESCE(SUM(contract_value), 0) FROM contract.contracts WHERE project_id = ?1",
            projectId));

    long publishedTenders = queryScalarLong(
        "SELECT COUNT(*) FROM contract.tenders WHERE project_id = ?1", projectId);

    long openNCRs = 0; // no NCR table seeded

    int total = 5;
    int pass = (sanctionOk ? 1 : 0)
        + (contractorCount > 0 ? 1 : 0)
        + (linkedOrders > 0 ? 1 : 0)
        + (publishedTenders > 0 ? 1 : 0)
        + 1; // parivesh stub
    double score = total > 0 ? pass * 100.0 / total : 0.0;

    return ApiResponse.ok(new ProjectComplianceDto(
        new PfmsBlock(sanctionOk, null, BigDecimal.ZERO),
        new GstnBlock(contractorCount, contractorCount, 0),
        new GemBlock(linkedOrders, gemValueCrores),
        new CpppBlock(publishedTenders, 0),
        new PariveshBlock(0, 0),
        new HseBlock(0, openNCRs),
        score));
  }

  // ─────────────── J11 — Variation orders ───────────────

  @GetMapping("/variation-orders")
  @SuppressWarnings("unchecked")
  public ApiResponse<List<VariationOrderRow>> getVariationOrders(@PathVariable UUID projectId) {
    try {
      List<Object> rows = em.createNativeQuery(
              "SELECT vo.id, vo.contract_id, vo.vo_number, vo.description, "
                  + "       COALESCE(vo.impact_on_budget, vo.vo_value, 0), "
                  + "       vo.impact_on_schedule_days, vo.status, vo.approved_by, "
                  + "       CAST(vo.approved_at AS date) "
                  + "FROM contract.variation_orders vo "
                  + "JOIN contract.contracts c ON c.id = vo.contract_id "
                  + "WHERE c.project_id = ?1 "
                  + "ORDER BY vo.approved_at DESC NULLS LAST, vo.vo_number")
          .setParameter(1, projectId)
          .getResultList();
      List<VariationOrderRow> result = new ArrayList<>(rows.size());
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        result.add(new VariationOrderRow(
            UUID.fromString(cols[0].toString()),
            cols[1] != null ? UUID.fromString(cols[1].toString()) : null,
            cols[2] != null ? cols[2].toString() : "",
            cols[3] != null ? cols[3].toString() : "",
            cols[4] != null ? toCrores(new BigDecimal(cols[4].toString())) : BigDecimal.ZERO,
            cols[5] != null ? ((Number) cols[5]).intValue() : null,
            cols[6] != null ? cols[6].toString() : "",
            cols[7] != null ? cols[7].toString() : null,
            cols[8] != null ? LocalDate.parse(cols[8].toString()) : null));
      }
      return ApiResponse.ok(result);
    } catch (Exception e) {
      log.debug("variation-orders query failed: {}", e.getMessage());
      return ApiResponse.ok(List.of());
    }
  }

  // ─────────────── J12 — Schedule quality (DCMA-14) ───────────────

  public record ScheduleQualityDto(
      long missingLogicCount,
      long leadRelationshipsCount,
      long lagsCount,
      double fsRelationshipPct,
      long hardConstraintsCount,
      long highFloatCount,
      long negativeFloatCount,
      long invalidDatesCount,
      long resourceAllocationIssues,
      long missedTasksCount,
      boolean criticalPathTestOk,
      long criticalPathLength,
      double beiActual,
      double beiRequired,
      double overallHealthPct,
      List<String> failingChecks) {}

  @GetMapping("/schedule-quality")
  public ApiResponse<ScheduleQualityDto> getScheduleQuality(@PathVariable UUID projectId) {
    long missingLogic = queryScalarLong(
        "SELECT COUNT(a.id) FROM activity.activities a "
            + "LEFT JOIN activity.activity_relationships r "
            + "  ON (r.predecessor_activity_id = a.id OR r.successor_activity_id = a.id) "
            + "WHERE a.project_id = ?1 AND r.id IS NULL",
        projectId);
    long leadRels = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activity_relationships r "
            + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
            + "WHERE a.project_id = ?1 AND r.lag < 0",
        projectId);
    long lags = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activity_relationships r "
            + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
            + "WHERE a.project_id = ?1 AND r.lag > 0",
        projectId);
    long totalRels = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activity_relationships r "
            + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
            + "WHERE a.project_id = ?1",
        projectId);
    long fsRels = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activity_relationships r "
            + "JOIN activity.activities a ON a.id = r.predecessor_activity_id "
            + "WHERE a.project_id = ?1 AND r.relationship_type = 'FINISH_TO_START'",
        projectId);
    double fsPct = totalRels > 0 ? (fsRels * 100.0) / totalRels : 100.0;
    long hardConstraints = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities "
            + "WHERE project_id = ?1 AND primary_constraint_type IN ('START_ON','FINISH_ON','AS_LATE_AS_POSSIBLE')",
        projectId);
    long highFloat = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND total_float > 44",
        projectId);
    long negFloat = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND total_float < 0",
        projectId);
    long invalidDates = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities "
            + "WHERE project_id = ?1 AND planned_start_date IS NOT NULL AND planned_finish_date IS NOT NULL "
            + "  AND planned_start_date > planned_finish_date",
        projectId);
    long resAllocIssues = queryScalarLong(
        "SELECT COUNT(a.id) FROM activity.activities a "
            + "LEFT JOIN resource.resource_assignments ra ON ra.activity_id = a.id "
            + "WHERE a.project_id = ?1 AND ra.id IS NULL",
        projectId);
    long missedTasks = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities "
            + "WHERE project_id = ?1 AND planned_finish_date < CURRENT_DATE "
            + "  AND (percent_complete IS NULL OR percent_complete < 100)",
        projectId);
    long cpLength = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities WHERE project_id = ?1 AND is_critical = TRUE",
        projectId);
    boolean cpOk = cpLength > 0;

    long completedByNow = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities "
            + "WHERE project_id = ?1 AND percent_complete >= 100",
        projectId);
    long shouldHave = queryScalarLong(
        "SELECT COUNT(*) FROM activity.activities "
            + "WHERE project_id = ?1 AND planned_finish_date <= CURRENT_DATE",
        projectId);
    double beiActual = shouldHave > 0 ? (completedByNow * 1.0) / shouldHave : 0.0;
    double beiRequired = 0.95;

    int checks = 14;
    int failed = 0;
    List<String> failing = new ArrayList<>();
    if (missingLogic > 0) { failed++; failing.add("Missing logic"); }
    if (leadRels > 0) { failed++; failing.add("Leads (negative lag)"); }
    if (lags > totalRels * 0.1) { failed++; failing.add("Excessive lags (>10%)"); }
    if (fsPct < 90) { failed++; failing.add("FS relationships <90%"); }
    if (hardConstraints > 0) { failed++; failing.add("Hard constraints present"); }
    if (highFloat > 0) { failed++; failing.add("High float activities (>44d)"); }
    if (negFloat > 0) { failed++; failing.add("Negative float"); }
    if (invalidDates > 0) { failed++; failing.add("Invalid date order"); }
    if (resAllocIssues > 0) { failed++; failing.add("Activities without resources"); }
    if (missedTasks > 0) { failed++; failing.add("Missed tasks"); }
    if (!cpOk) { failed++; failing.add("No critical path identified"); }
    if (beiActual < beiRequired) { failed++; failing.add("BEI below target"); }
    double health = ((checks - failed) * 100.0) / checks;

    return ApiResponse.ok(new ScheduleQualityDto(
        missingLogic, leadRels, lags, fsPct, hardConstraints, highFloat, negFloat,
        invalidDates, resAllocIssues, missedTasks, cpOk, cpLength,
        beiActual, beiRequired, health, failing));
  }

  // ─────────────── J14 — Milestone tracker ───────────────

  @GetMapping("/milestones")
  @SuppressWarnings("unchecked")
  public ApiResponse<List<MilestoneRow>> getMilestones(@PathVariable UUID projectId) {
    try {
      List<Object> rows = em.createNativeQuery(
              "SELECT id, code, name, activity_type, "
                  + "       planned_finish_date, planned_finish_date, actual_finish_date, status, "
                  + "       percent_complete "
                  + "FROM activity.activities "
                  + "WHERE project_id = ?1 "
                  + "  AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE') "
                  + "ORDER BY planned_finish_date")
          .setParameter(1, projectId)
          .getResultList();

      LocalDate today = LocalDate.now();
      List<MilestoneRow> result = new ArrayList<>(rows.size());
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        LocalDate plannedDate = cols[4] != null ? LocalDate.parse(cols[4].toString()) : null;
        LocalDate forecastDate = cols[5] != null ? LocalDate.parse(cols[5].toString()) : null;
        LocalDate actualDate = cols[6] != null ? LocalDate.parse(cols[6].toString()) : null;
        String status = cols[7] != null ? cols[7].toString() : "";
        long slip = 0;
        if (plannedDate != null && actualDate != null && actualDate.isAfter(plannedDate)) {
          slip = ChronoUnit.DAYS.between(plannedDate, actualDate);
        } else if (plannedDate != null && actualDate == null && today.isAfter(plannedDate)) {
          slip = ChronoUnit.DAYS.between(plannedDate, today);
        }
        result.add(new MilestoneRow(
            UUID.fromString(cols[0].toString()),
            cols[1] != null ? cols[1].toString() : "",
            cols[2] != null ? cols[2].toString() : "",
            cols[3] != null ? cols[3].toString() : "",
            plannedDate, forecastDate, actualDate,
            status, slip, BigDecimal.ZERO));
      }
      result.sort(Comparator.comparing(MilestoneRow::plannedDate,
          Comparator.nullsLast(Comparator.naturalOrder())));
      return ApiResponse.ok(result);
    } catch (Exception e) {
      log.debug("milestones query failed: {}", e.getMessage());
      return ApiResponse.ok(List.of());
    }
  }

  // ─────────────── helpers ───────────────

  private static double computePlannedPct(LocalDate today, LocalDate start, LocalDate finish) {
    if (start == null || finish == null) return 0.0;
    if (!today.isAfter(start)) return 0.0;
    if (!today.isBefore(finish)) return 100.0;
    long total = ChronoUnit.DAYS.between(start, finish);
    if (total <= 0) return 100.0;
    long elapsed = ChronoUnit.DAYS.between(start, today);
    return Math.max(0.0, Math.min(100.0, (elapsed * 100.0) / total));
  }

  private static BigDecimal nullToZero(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal toCrores(BigDecimal v) {
    if (v == null || v.signum() == 0) return BigDecimal.ZERO;
    return v.divide(CRORE, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal scaleMoney(BigDecimal v) {
    return v != null ? v.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
  }

  private static String bandOne(double idx) {
    if (idx == 0.0) return "GREEN";
    if (idx >= 0.95) return "GREEN";
    if (idx >= 0.85) return "AMBER";
    return "RED";
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

  private double queryScalarDouble(String sql, Object... params) {
    try {
      var q = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
      Object r = q.getSingleResult();
      return r != null ? ((Number) r).doubleValue() : 0.0;
    } catch (Exception e) {
      return 0.0;
    }
  }

  private BigDecimal queryScalarBigDecimal(String sql, Object... params) {
    try {
      var q = em.createNativeQuery(sql);
      for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
      Object r = q.getSingleResult();
      return r != null ? new BigDecimal(r.toString()) : BigDecimal.ZERO;
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }
}
