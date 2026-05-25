package com.bipros.api.service;

import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

/**
 * Sub-contractor KPIs for the Operational Insights tab. Mirrors the structure of
 * {@link ManpowerKpiService} but reads from sub-contractor master tables and the
 * dpr_sub_contractor join. Computes Quantity Completion, Productivity Factor (vs
 * sub_contractor_work_activity_mappings.output_per_day norm), Cost Performance Index,
 * Cost Variance per (sub-contractor, work-type) pair.
 *
 * <p>Single native SQL query with two CTEs (plan + actual) avoids the row-multiplication
 * bug that would occur if multiple activities share the same (SC, work-type) pair —
 * the CTE aggregates per (sc, work-type) first, then joins 1-to-1 to display fields.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SubContractorKpiService {

  private final SubContractorMasterRepository subContractorMasterRepository;
  private final SubContractorWorkActivityMappingRepository workActivityMappingRepository;
  private final ActivitySubContractorAssignmentRepository assignmentRepository;
  private final DprSubContractorRepository dprSubContractorRepository;
  private final DailyProgressReportRepository dprRepository;

  @PersistenceContext
  private EntityManager em;

  public record SubContractorKpiResponse(
      UUID projectId,
      LocalDate from,
      LocalDate to,
      int activeSubContractors,
      int workTypesTracked,
      BigDecimal totalPlannedQty,
      BigDecimal totalActualQty,
      BigDecimal quantityCompletionPct,
      BigDecimal avgProductivityFactor,
      BigDecimal totalPlannedCost,
      BigDecimal totalActualCost,
      BigDecimal costVariance,
      BigDecimal costPerformanceIndex,
      int daysWorked,
      Integer impliedPlannedDays,
      int underPerformingCount,
      int unmatchedDprRows,
      List<SubContractorWorkTypeRow> perScWorkType,
      List<SubContractorWorkTypeRow> bottomProductivity,
      List<SubContractorWorkTypeRow> topByCost,
      List<SubContractorWorkTypeRow> bottomOutputAchievement
  ) {}

  public record SubContractorWorkTypeRow(
      UUID scMasterId, String scCode, String scName,
      UUID scWorkTypeId, String workTypeName,
      String unit, BigDecimal ratePerUnit, BigDecimal normPerDay,
      BigDecimal plannedQty, BigDecimal actualQty,
      int distinctDays, BigDecimal avgQtyPerDay,
      BigDecimal productivityFactor,
      BigDecimal plannedCost, BigDecimal actualCost,
      BigDecimal costVariance, BigDecimal costPerformanceIndex,
      BigDecimal qtyCompletionPct
  ) {}

  @SuppressWarnings("unchecked")
  public SubContractorKpiResponse compute(UUID projectId, LocalDate from, LocalDate to) {
    if (projectId == null) {
      return emptyResponse(projectId, from, to);
    }

    // Aggregate in two CTEs so the join doesn't multiply actual qty when multiple activities
    // share the same (sc, work-type). Plan CTE sums planned qty/cost across all activities of
    // a (sc, work-type) pair. Actual CTE sums DPR qty/cost and distinct days for the same pair.
    // The outer query joins them 1-to-1 and adds SC master + work-type display names + norm.
    String sql = "WITH plan AS ( "
        + "  SELECT a.sub_contractor_master_id, a.sc_work_type_id, "
        + "         MAX(a.unit) AS unit, MAX(a.rate_per_unit) AS rate_per_unit, "
        + "         MAX(a.work_type_name) AS work_type_name_fallback, "
        + "         SUM(a.planned_units) AS planned_qty, "
        + "         SUM(a.planned_cost)  AS planned_cost "
        + "    FROM resource.activity_sub_contractor_assignments a "
        + "   WHERE a.project_id = :projectId "
        + "   GROUP BY a.sub_contractor_master_id, a.sc_work_type_id "
        + "), actual AS ( "
        + "  SELECT a.sub_contractor_master_id, a.sc_work_type_id, "
        + "         SUM(c.quantity) AS actual_qty, "
        + "         SUM(c.quantity * COALESCE(a.rate_per_unit, 0)) AS actual_cost, "
        + "         COUNT(DISTINCT d.report_date) AS distinct_days "
        + "    FROM project.dpr_sub_contractor c "
        + "    JOIN project.daily_progress_reports d ON d.id = c.dpr_id "
        + "    JOIN resource.activity_sub_contractor_assignments a ON a.id = c.activity_sub_contractor_assignment_id "
        + "   WHERE d.project_id = :projectId "
        + "     AND d.report_date BETWEEN :fromDate AND :toDate "
        + "   GROUP BY a.sub_contractor_master_id, a.sc_work_type_id "
        + ") "
        + "SELECT plan.sub_contractor_master_id, sm.code, sm.name, "
        + "       plan.sc_work_type_id, COALESCE(wt.name, plan.work_type_name_fallback) AS work_type_name, "
        + "       plan.unit, plan.rate_per_unit, m.output_per_day, "
        + "       plan.planned_qty, plan.planned_cost, "
        + "       COALESCE(actual.actual_qty, 0)     AS actual_qty, "
        + "       COALESCE(actual.actual_cost, 0)    AS actual_cost, "
        + "       COALESCE(actual.distinct_days, 0)  AS distinct_days "
        + "  FROM plan "
        + "  JOIN resource.sub_contractor_master sm ON sm.id = plan.sub_contractor_master_id "
        + "  LEFT JOIN resource.subcontractor_work_types wt ON wt.id = plan.sc_work_type_id "
        + "  LEFT JOIN resource.sub_contractor_work_activity_mappings m "
        + "         ON m.sub_contractor_master_id = plan.sub_contractor_master_id "
        + "        AND m.sc_work_type_id = plan.sc_work_type_id "
        + "  LEFT JOIN actual ON actual.sub_contractor_master_id = plan.sub_contractor_master_id "
        + "                 AND actual.sc_work_type_id = plan.sc_work_type_id";

    List<Object[]> rows = em.createNativeQuery(sql)
        .setParameter("projectId", projectId)
        .setParameter("fromDate", from)
        .setParameter("toDate", to)
        .getResultList();

    // Count orphan DPR SC rows (FK to assignment is null — see spec §8 / out-of-scope ticket E)
    String orphanSql = "SELECT COUNT(*) FROM project.dpr_sub_contractor c "
        + "JOIN project.daily_progress_reports d ON d.id = c.dpr_id "
        + "WHERE d.project_id = :projectId "
        + "  AND d.report_date BETWEEN :fromDate AND :toDate "
        + "  AND c.activity_sub_contractor_assignment_id IS NULL";
    Number orphan = (Number) em.createNativeQuery(orphanSql)
        .setParameter("projectId", projectId)
        .setParameter("fromDate", from)
        .setParameter("toDate", to)
        .getSingleResult();
    int unmatchedDprRows = orphan == null ? 0 : orphan.intValue();

    List<SubContractorWorkTypeRow> perScWorkType = new ArrayList<>();
    Set<UUID> distinctScIds = new HashSet<>();
    BigDecimal totalPlannedQty = BigDecimal.ZERO;
    BigDecimal totalActualQty = BigDecimal.ZERO;
    BigDecimal totalPlannedCost = BigDecimal.ZERO;
    BigDecimal totalActualCost = BigDecimal.ZERO;
    int totalDistinctDays = 0;
    int underPerforming = 0;
    List<BigDecimal> pfValues = new ArrayList<>();

    for (Object[] r : rows) {
      UUID scMasterId = (UUID) r[0];
      String scCode = (String) r[1];
      String scName = (String) r[2];
      UUID scWorkTypeId = (UUID) r[3];
      String workTypeName = (String) r[4];
      String unit = (String) r[5];
      BigDecimal ratePerUnit = (BigDecimal) r[6];
      BigDecimal normPerDay = (BigDecimal) r[7];
      BigDecimal plannedQty = bd(r[8]);
      BigDecimal plannedCost = bd(r[9]);
      BigDecimal actualQty = bd(r[10]);
      BigDecimal actualCost = bd(r[11]);
      int distinctDays = r[12] instanceof Number n ? n.intValue() : 0;

      distinctScIds.add(scMasterId);
      totalPlannedQty = totalPlannedQty.add(plannedQty);
      totalActualQty = totalActualQty.add(actualQty);
      totalPlannedCost = totalPlannedCost.add(plannedCost);
      totalActualCost = totalActualCost.add(actualCost);
      totalDistinctDays = Math.max(totalDistinctDays, distinctDays);

      BigDecimal avgQtyPerDay = distinctDays > 0
          ? actualQty.divide(BigDecimal.valueOf(distinctDays), 4, RoundingMode.HALF_UP)
          : BigDecimal.ZERO;
      BigDecimal productivityFactor = null;
      if (normPerDay != null && normPerDay.signum() > 0 && distinctDays > 0) {
        productivityFactor = avgQtyPerDay.divide(normPerDay, 4, RoundingMode.HALF_UP);
        pfValues.add(productivityFactor);
        if (productivityFactor.compareTo(new BigDecimal("0.8")) < 0) underPerforming++;
      }
      BigDecimal costVariance = plannedCost.subtract(actualCost);
      BigDecimal cpi = actualCost.signum() > 0
          ? plannedCost.divide(actualCost, 4, RoundingMode.HALF_UP)
          : null;
      BigDecimal qtyComplete = plannedQty.signum() > 0
          ? actualQty.multiply(new BigDecimal("100"))
              .divide(plannedQty, 2, RoundingMode.HALF_UP)
          : BigDecimal.ZERO;

      perScWorkType.add(new SubContractorWorkTypeRow(
          scMasterId, scCode, scName, scWorkTypeId, workTypeName,
          unit, ratePerUnit, normPerDay,
          plannedQty, actualQty, distinctDays, avgQtyPerDay,
          productivityFactor,
          plannedCost, actualCost, costVariance, cpi, qtyComplete));
    }

    BigDecimal totalQtyComplete = totalPlannedQty.signum() > 0
        ? totalActualQty.multiply(new BigDecimal("100"))
            .divide(totalPlannedQty, 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;

    BigDecimal avgPf = null;
    if (!pfValues.isEmpty()) {
      BigDecimal sum = pfValues.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      avgPf = sum.divide(BigDecimal.valueOf(pfValues.size()), 4, RoundingMode.HALF_UP);
    }

    BigDecimal totalCostVariance = totalPlannedCost.subtract(totalActualCost);
    BigDecimal totalCpi = totalActualCost.signum() > 0
        ? totalPlannedCost.divide(totalActualCost, 4, RoundingMode.HALF_UP)
        : null;

    // Implied planned days: total planned qty / weighted-avg norm (skip rows without norm)
    Integer impliedPlannedDays = null;
    BigDecimal sumPlannedForNormed = BigDecimal.ZERO;
    BigDecimal weightedNormSum = BigDecimal.ZERO;
    for (SubContractorWorkTypeRow row : perScWorkType) {
      if (row.normPerDay() != null && row.normPerDay().signum() > 0) {
        sumPlannedForNormed = sumPlannedForNormed.add(row.plannedQty());
        weightedNormSum = weightedNormSum.add(row.plannedQty().multiply(row.normPerDay()));
      }
    }
    if (sumPlannedForNormed.signum() > 0 && weightedNormSum.signum() > 0) {
      BigDecimal avgNorm = weightedNormSum.divide(sumPlannedForNormed, 4, RoundingMode.HALF_UP);
      if (avgNorm.signum() > 0) {
        impliedPlannedDays = sumPlannedForNormed
            .divide(avgNorm, 0, RoundingMode.CEILING)
            .intValue();
      }
    }

    List<SubContractorWorkTypeRow> bottomPf = perScWorkType.stream()
        .filter(r -> r.productivityFactor() != null)
        .sorted(Comparator.comparing(SubContractorWorkTypeRow::productivityFactor))
        .limit(5)
        .toList();
    List<SubContractorWorkTypeRow> topByCost = perScWorkType.stream()
        .sorted(Comparator.comparing(SubContractorWorkTypeRow::actualCost).reversed())
        .limit(5)
        .toList();
    List<SubContractorWorkTypeRow> bottomOutput = perScWorkType.stream()
        .sorted(Comparator.comparing(SubContractorWorkTypeRow::qtyCompletionPct))
        .limit(5)
        .toList();

    return new SubContractorKpiResponse(
        projectId, from, to,
        distinctScIds.size(), perScWorkType.size(),
        totalPlannedQty, totalActualQty, totalQtyComplete,
        avgPf,
        totalPlannedCost, totalActualCost, totalCostVariance, totalCpi,
        totalDistinctDays, impliedPlannedDays, underPerforming, unmatchedDprRows,
        perScWorkType, bottomPf, topByCost, bottomOutput);
  }

  private SubContractorKpiResponse emptyResponse(UUID projectId, LocalDate from, LocalDate to) {
    return new SubContractorKpiResponse(
        projectId, from, to,
        0, 0,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        null,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
        0, null, 0, 0,
        List.of(), List.of(), List.of(), List.of());
  }

  private static BigDecimal bd(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal b) return b;
    return new BigDecimal(o.toString());
  }
}
