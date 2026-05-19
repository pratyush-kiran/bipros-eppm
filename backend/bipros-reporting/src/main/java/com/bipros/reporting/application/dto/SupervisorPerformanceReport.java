package com.bipros.reporting.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supervisor Performance — supervisor-aware supplement to the Capacity Utilization report.
 * Mirrors the client's monthly SC180 Resource Productivity Report:
 * <ul>
 *   <li>{@link Summary} — two flat rollup tables (per-trade Manpower Utilization and per-equipment-
 *       type Equipment Utilization) with budgeted-vs-actual man-days/equipment-days, % utilization,
 *       and cost implication.</li>
 *   <li>{@link ActivityDrillDown} — per-activity breakdown with productivity-norm comparison and
 *       free-text remarks.</li>
 * </ul>
 *
 * <p>When {@code supervisorUserId} is null the report aggregates project-wide (every DPR in
 * the date window). When set, only DPRs filed under that supervisor contribute.
 *
 * <p>RBAC Phase 4.4 — the identity field carries a User UUID (FK to {@code public.users.id})
 * after the OLTP rename in migration 091.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorPerformanceReport(
    UUID projectId,
    UUID supervisorUserId,
    String supervisorName,
    LocalDate fromDate,
    LocalDate toDate,
    int workDays,
    Summary summary,
    List<ActivityDrillDown> activities) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(
      List<TradeRollup> manpower,
      List<EquipmentRollup> equipment) {}

  /** One row of the SC180 "Manpower Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TradeRollup(
      String tradeKey,            // canonical: ResourceRole.code or UPPER(TRIM(dpr_manpower.trade))
      String tradeLabel,          // ResourceRole.name or raw trade text
      BigDecimal mmRate,          // Σ(line_cost) / Σ(actualManDays)
      BigDecimal qtyDone,         // activity output executed where this trade was present
      BigDecimal budgetedManDays, // Σ over activities (qty / output_per_man_per_day)
      BigDecimal actualManDays,   // Σ(nos) — raw headcount-days, hours ignored
      BigDecimal utilizationPct,  // (budgetedManDays / actualManDays) × 100, capped 999
      BigDecimal costImplication, // (actualManDays - budgetedManDays) × mmRate
      String normSource) {}        // SPECIFIC_RESOURCE | RESOURCE_TYPE | RESOURCE_LEGACY | NONE

  /** One row of the SC180 "Equipment Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EquipmentRollup(
      String equipmentKey,
      String equipmentLabel,
      BigDecimal hourRate,        // Σ(line_cost) / Σ(actualDays)
      BigDecimal qtyDone,
      BigDecimal budgetedDays,
      BigDecimal actualDays,      // Σ(nos) — raw equipment-days, hours ignored
      BigDecimal utilizationPct,
      BigDecimal costImplication,
      String normSource) {}

  /** Per-activity drill-down — mirrors the SC180 "PR for MP and Eqt" sheet. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivityDrillDown(
      UUID activityId,
      String activityCode,
      String activityName,
      String unit,
      BigDecimal qtyForMonth,
      List<ResourceLine> resources,
      String remarks) {}

  /** One row inside an {@link ActivityDrillDown}: either a manpower trade or an equipment type. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResourceLine(
      String kind,                // MANPOWER | EQUIPMENT
      String resourceKey,
      String resourceLabel,
      ProductivityNorms norms,
      PlannedActuals planMonth,
      PlannedActuals actualMonth) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ProductivityNorms(
      BigDecimal budget,          // from productivity_norms (norm output_per_man_per_day or output_per_day)
      BigDecimal projection,      // null in MVP — wire up when a projections store exists
      BigDecimal actualsFtm,      // actual output per day for this (activity × resource) — qty / actualDays
      String normSource) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PlannedActuals(
      BigDecimal qty,
      BigDecimal budgetDays,
      BigDecimal days,
      BigDecimal utilizationPct) {}
}
