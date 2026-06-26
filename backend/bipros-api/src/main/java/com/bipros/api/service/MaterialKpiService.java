package com.bipros.api.service;

import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.domain.model.MaterialIssue;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.MaterialIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NH-48 KPI Framework Phase 1 — Material KPIs from existing entities only.
 *
 * <p>Inputs come from three sources:
 * <ul>
 *   <li>{@code material_issues} — what left the store (qty + wastage)</li>
 *   <li>{@code material_consumption_logs} — opening/closing stock + consumed/wastage% per day</li>
 *   <li>{@code dpr_material} — what each DPR row reports as consumed (with actual unit_rate)</li>
 * </ul>
 *
 * <p>KPIs requiring "planned qty" (8.4 Consumption Efficiency, 9.2 Usage Variance) need a BOQ
 * baseline that isn't stored per material. They surface as {@code null} in Phase 1 — the UI
 * shows a "—" placeholder rather than fabricating a number. Full coverage of those KPIs is
 * tracked in Phase 2 (material_batch_designs schema addition).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaterialKpiService {

  private final MaterialIssueRepository issueRepository;
  private final MaterialConsumptionLogRepository consumptionLogRepository;
  private final DailyProgressReportRepository dprRepository;
  private final DprMaterialRepository dprMaterialRepository;
  private final BoqItemRepository boqItemRepository;

  // ---------- Response shapes ----------

  public record MaterialKpiResponse(
      UUID projectId,
      LocalDate from,
      LocalDate to,
      double issuedQty,
      double consumedQty,
      double wastageQty,
      double materialUtilizationPct,
      double wastagePct,
      double reconciliationBalance,
      Double materialPriceVariance,
      Double materialUsageVariance,
      Double totalMaterialCostVariance,
      List<MaterialBreakdownRow> byMaterial,
      double weightedAvgCostPerUnitFinished,
      List<CostPerUnitRow> costPerUnitByActivity
  ) {}

  public record MaterialBreakdownRow(
      String materialName,
      double issuedQty,
      double consumedQty,
      double wastageQty,
      double utilizationPct,
      double avgUnitRate
  ) {}

  /**
   * KPI 9.5 — Material Cost / Unit Finished Work, per activity.
   * {@code costPerUnit = Σ dpr_material.line_cost ÷ Σ DPR.qty_executed} for the activity.
   * {@code boqBudgetedRate} is the matched BOQ row's budgeted rate; null if no match.
   * {@code varianceVsBoqPct} is positive (favourable) when actual cost &lt; budgeted.
   */
  public record CostPerUnitRow(
      UUID activityId,
      String activityName,
      double materialCost,
      double qtyFinished,
      double costPerUnit,
      Double boqBudgetedRate,
      Double varianceVsBoqPct
  ) {}

  // ---------- Public API ----------

  @Transactional(readOnly = true)
  public MaterialKpiResponse compute(UUID projectId, LocalDate from, LocalDate to) {
    List<MaterialIssue> issues = issueRepository.findByProjectIdAndIssueDateBetween(projectId, from, to);
    List<MaterialConsumptionLog> logs = consumptionLogRepository
        .findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(projectId, from, to);
    List<DprMaterial> dprMaterials = fetchDprMaterials(projectId, from, to);

    double issuedQty = issues.stream()
        .map(MaterialIssue::getQuantity)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(BigDecimal::doubleValue)
        .sum();
    double issueWastage = issues.stream()
        .map(MaterialIssue::getWastageQuantity)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(BigDecimal::doubleValue)
        .sum();
    double consumedQty = logs.stream()
        .map(MaterialConsumptionLog::getConsumed)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(BigDecimal::doubleValue)
        .sum();

    // Wastage = issued − consumed (clamped to ≥ 0). Returns are not modelled today, so
    // negative values would imply a data-quality issue rather than legitimate over-consumption.
    double wastageQty = Math.max(0d, issuedQty - consumedQty);
    if (wastageQty == 0d && issueWastage > 0d) wastageQty = issueWastage;

    double utilizationPct = issuedQty > 0 ? consumedQty / issuedQty : 0d;
    double wastagePct = issuedQty > 0 ? wastageQty / issuedQty : 0d;
    double reconBalance = issuedQty - consumedQty - wastageQty;

    // KPI 9.x — variance from DPR materials. Std rate is unavailable in Phase 1 (no BOQ ↔
    // material map), so price / usage variances are surfaced as null. Average actual rate is
    // still useful in the per-material breakdown.
    Double priceVariance = null;
    Double usageVariance = null;
    Double totalVariance = null;

    List<MaterialBreakdownRow> breakdown = computeBreakdown(issues, logs, dprMaterials);

    // KPI 9.5 — Material Cost / Unit Finished Work
    List<DailyProgressReport> dprs = dprRepository
        .findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, DprApprovalStatus.APPROVED, from, to);
    List<BoqItem> boqItems = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    List<CostPerUnitRow> costRows = computeCostPerUnitFinished(dprs, dprMaterials, boqItems);
    double weightedCpu = computeWeightedCpu(costRows);

    return new MaterialKpiResponse(
        projectId,
        from,
        to,
        round3(issuedQty),
        round3(consumedQty),
        round3(wastageQty),
        round4(utilizationPct),
        round4(wastagePct),
        round3(reconBalance),
        priceVariance,
        usageVariance,
        totalVariance,
        breakdown,
        round2(weightedCpu),
        costRows);
  }

  // ---------- KPI 9.5 — Cost / Unit Finished Work ----------

  /**
   * Roll up DPR-material line cost and parent DPR qty_executed by activity. Compare to BOQ
   * budgeted rate when matched. Variance % positive = under budget (favourable).
   */
  private List<CostPerUnitRow> computeCostPerUnitFinished(
      List<DailyProgressReport> dprs,
      List<DprMaterial> dprMaterials,
      List<BoqItem> boqItems) {
    if (dprs.isEmpty()) return List.of();
    Map<UUID, double[]> byActivity = new HashMap<>(); // [cost, qty]
    Map<UUID, String> activityNames = new HashMap<>();
    Map<UUID, UUID> activityForDpr = dprs.stream()
        .filter(d -> d.getActivityId() != null)
        .collect(Collectors.toMap(DailyProgressReport::getId,
            DailyProgressReport::getActivityId, (a, b) -> a));
    for (DailyProgressReport d : dprs) {
      if (d.getActivityId() == null) continue;
      double qty = d.getQtyExecuted() != null ? d.getQtyExecuted().doubleValue() : 0d;
      double[] acc = byActivity.computeIfAbsent(d.getActivityId(), k -> new double[2]);
      acc[1] += qty;
      activityNames.putIfAbsent(d.getActivityId(), d.getActivityName());
    }
    for (DprMaterial m : dprMaterials) {
      UUID activityId = activityForDpr.get(m.getDprId());
      if (activityId == null) continue;
      double cost = m.getLineCost() != null ? m.getLineCost().doubleValue() : 0d;
      double[] acc = byActivity.computeIfAbsent(activityId, k -> new double[2]);
      acc[0] += cost;
    }
    List<CostPerUnitRow> rows = new java.util.ArrayList<>(byActivity.size());
    for (Map.Entry<UUID, double[]> e : byActivity.entrySet()) {
      double cost = e.getValue()[0];
      double qty = e.getValue()[1];
      if (cost <= 0d) continue; // skip activities with no material cost
      double cpu = qty > 0d ? cost / qty : 0d;
      String name = activityNames.getOrDefault(e.getKey(), "?");
      BoqItem boq = matchBoq(boqItems, name);
      Double boqRate = (boq != null && boq.getBudgetedRate() != null)
          ? boq.getBudgetedRate().doubleValue() : null;
      Double variancePct = (boqRate != null && boqRate > 0d)
          ? (boqRate - cpu) / boqRate
          : null;
      rows.add(new CostPerUnitRow(
          e.getKey(), name,
          round2(cost), round3(qty), round2(cpu),
          boqRate != null ? round2(boqRate) : null,
          variancePct != null ? round4(variancePct) : null));
    }
    rows.sort(Comparator.comparingDouble(CostPerUnitRow::costPerUnit).reversed());
    return rows;
  }

  private double computeWeightedCpu(List<CostPerUnitRow> rows) {
    double totalCost = 0d;
    double totalQty = 0d;
    for (CostPerUnitRow r : rows) {
      totalCost += r.materialCost();
      totalQty += r.qtyFinished();
    }
    return totalQty > 0d ? totalCost / totalQty : 0d;
  }

  private static BoqItem matchBoq(List<BoqItem> boq, String activityName) {
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

  private List<DprMaterial> fetchDprMaterials(UUID projectId, LocalDate from, LocalDate to) {
    List<DailyProgressReport> dprs = dprRepository
        .findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, DprApprovalStatus.APPROVED, from, to);
    if (dprs.isEmpty()) return List.of();
    Set<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).collect(Collectors.toSet());
    return dprMaterialRepository.findByDprIdIn(dprIds);
  }

  private List<MaterialBreakdownRow> computeBreakdown(
      List<MaterialIssue> issues,
      List<MaterialConsumptionLog> logs,
      List<DprMaterial> dprMaterials) {

    Map<String, double[]> byName = new HashMap<>(); // [issued, consumed, wastage, rateSum, rateCount]
    for (MaterialConsumptionLog l : logs) {
      String name = l.getMaterialName();
      if (name == null) continue;
      double[] acc = byName.computeIfAbsent(name, k -> new double[5]);
      if (l.getConsumed() != null) acc[1] += l.getConsumed().doubleValue();
    }
    for (DprMaterial m : dprMaterials) {
      String name = m.getMaterialName();
      if (name == null) continue;
      double[] acc = byName.computeIfAbsent(name, k -> new double[5]);
      if (m.getQuantity() != null) acc[2] += 0d; // placeholder so name shows up
      if (m.getUnitRate() != null) {
        acc[3] += m.getUnitRate().doubleValue();
        acc[4] += 1d;
      }
    }
    // Issues are keyed by material_id, not name; pulling the name requires a join we skip
    // in Phase 1. The aggregate "issuedQty" still flows into the headline KPIs above.
    for (MaterialIssue i : issues) {
      // No-op: per-material breakdown by name relies on consumption-log + DPR.
    }

    List<MaterialBreakdownRow> rows = new java.util.ArrayList<>(byName.size());
    for (Map.Entry<String, double[]> e : byName.entrySet()) {
      double[] v = e.getValue();
      double issued = v[0];
      double consumed = v[1];
      double wastage = Math.max(0d, issued - consumed);
      double util = issued > 0 ? consumed / issued : 0d;
      double avgRate = v[4] > 0 ? v[3] / v[4] : 0d;
      rows.add(new MaterialBreakdownRow(
          e.getKey(),
          round3(issued),
          round3(consumed),
          round3(wastage),
          round4(util),
          round2(avgRate)));
    }
    rows.sort(Comparator.comparingDouble(MaterialBreakdownRow::consumedQty).reversed());
    return rows;
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
}
