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
      List<EquipmentRollup> equipment,
      /** Per-activity banner notes for the manpower table — activities where manpower side was
       *  suppressed by the allocator. Reuses the SC180 HiddenSideNote shape. */
      List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> manpowerHiddenNotes,
      /** Same as {@link #manpowerHiddenNotes} but for the equipment table. */
      List<com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote> equipmentHiddenNotes) {
    /** Back-compat ctor — pre-existing call sites that don't yet supply hidden notes. */
    public Summary(List<TradeRollup> manpower, List<EquipmentRollup> equipment) {
      this(manpower, equipment, List.of(), List.of());
    }
  }

  /** One row of the SC180 "Manpower Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TradeRollup(
      String tradeKey,            // canonical: ResourceRole.code or UPPER(TRIM(dpr_manpower.trade))
      String tradeLabel,          // ResourceRole.name or raw trade text
      BigDecimal mmRate,          // Σ(line_cost) / Σ(actualManDays)
      BigDecimal qtyDone,         // ALLOCATED qty for this trade (per-DPR allocator share, not raw DPR qty)
      BigDecimal budgetedManDays, // Σ over (DPR,activity) (allocatedQty / output_per_man_per_day)
      BigDecimal actualManDays,   // Σ(nos) — raw headcount-days, hours ignored. Includes tracked + suppressed + untracked.
      /** Portion of {@link #actualManDays} on (DPR, activity) where this trade's manpower side was
       *  suppressed by the allocator (SERIES/SUBSTITUTE governed by equipment). Norm exists but
       *  this side didn't drive output. Null when zero. */
      BigDecimal actualDaysOnHiddenSides,
      /** Portion of {@link #actualManDays} where the trade's norm didn't resolve for the activity.
       *  Null when zero. */
      BigDecimal actualDaysUntracked,
      BigDecimal utilizationPct,  // (budgetedManDays / trackedManDays) × 100, capped 999
      BigDecimal costImplication, // (trackedManDays - budgetedManDays) × mmRate
      String normSource) {       // SPECIFIC_RESOURCE | RESOURCE_TYPE | RESOURCE_LEGACY | NONE
    /** Back-compat ctor — older callers can omit hidden/untracked breakdown. */
    public TradeRollup(String tradeKey, String tradeLabel, BigDecimal mmRate, BigDecimal qtyDone,
                       BigDecimal budgetedManDays, BigDecimal actualManDays,
                       BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(tradeKey, tradeLabel, mmRate, qtyDone, budgetedManDays, actualManDays,
          null, null, utilizationPct, costImplication, normSource);
    }
  }

  /** One row of the SC180 "Equipment Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EquipmentRollup(
      String equipmentKey,
      String equipmentLabel,
      BigDecimal hourRate,        // Σ(line_cost) / Σ(actualDays)
      BigDecimal qtyDone,         // ALLOCATED qty (per-DPR allocator share)
      BigDecimal budgetedDays,
      BigDecimal actualDays,      // Σ(nos) — raw equipment-days, hours ignored. Includes tracked + suppressed + untracked.
      /** See {@link TradeRollup#actualDaysOnHiddenSides}. */
      BigDecimal actualDaysOnHiddenSides,
      /** See {@link TradeRollup#actualDaysUntracked}. */
      BigDecimal actualDaysUntracked,
      BigDecimal utilizationPct,
      BigDecimal costImplication,
      String normSource) {
    /** Back-compat ctor — older callers can omit hidden/untracked breakdown. */
    public EquipmentRollup(String equipmentKey, String equipmentLabel, BigDecimal hourRate,
                           BigDecimal qtyDone, BigDecimal budgetedDays, BigDecimal actualDays,
                           BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(equipmentKey, equipmentLabel, hourRate, qtyDone, budgetedDays, actualDays,
          null, null, utilizationPct, costImplication, normSource);
    }
  }

  /** Per-activity drill-down — mirrors the SC180 "PR for MP and Eqt" sheet. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivityDrillDown(
      UUID activityId,
      String activityCode,
      String activityName,
      String unit,
      /** Total activity output for the window — sum of dpr.qty_executed across DPRs. Includes
       *  sub-contractor share. The frontend pairs this with {@link #subContractorQty} to show
       *  "200 Nos (170 own + 30 sub-contractor)". */
      BigDecimal qtyForMonth,
      /** Σ dpr_sub_contractor.quantity across DPRs in the window. Null when no sub-contractor
       *  rows exist (frontend then renders just the total qty without the breakdown). */
      BigDecimal subContractorQty,
      List<ResourceLine> resources,
      String remarks) {
    /** Back-compat ctor — older callers that don't pass subContractorQty. */
    public ActivityDrillDown(UUID activityId, String activityCode, String activityName,
                             String unit, BigDecimal qtyForMonth,
                             List<ResourceLine> resources, String remarks) {
      this(activityId, activityCode, activityName, unit, qtyForMonth, null, resources, remarks);
    }
  }

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
