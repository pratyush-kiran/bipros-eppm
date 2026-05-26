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
 *
 * <p><b>Time buckets (2026-05-25):</b> {@link TradeRollup}, {@link EquipmentRollup}, and
 * {@link ResourceLine} now carry optional bucketed breakdowns (Day / CalendarMonth / Cumulative)
 * alongside the legacy flat fields. The flat fields ({@code qtyForMonth}, {@code planMonth},
 * {@code actualMonth}, {@code qtyDone}, {@code utilizationPct} etc.) are
 * <b>cumulative-across-window</b> totals — the "Month" suffix is historical (predates the
 * generic windowed report) and is kept for backward compatibility with the frontend, Excel
 * writer, and Insights collector. New code should prefer the {@code buckets} field which
 * carries the three explicit time-period flavors anchored on the same {@code referenceDate}
 * rule as {@link CapacityUtilizationReport}: today if today falls inside [from,to], else to.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorPerformanceReport(
    UUID projectId,
    UUID supervisorUserId,
    String supervisorName,
    LocalDate fromDate,
    LocalDate toDate,
    int workDays,
    /** Reference day used to anchor the {@code day} and {@code calendarMonth} buckets — today
     *  if today falls inside [fromDate, toDate], otherwise {@code toDate}. Null on legacy
     *  responses (pre-2026-05-25). */
    LocalDate referenceDate,
    Summary summary,
    List<ActivityDrillDown> activities) {

  /** Back-compat ctor — older callers that don't supply a referenceDate. */
  public SupervisorPerformanceReport(
      UUID projectId, UUID supervisorUserId, String supervisorName,
      LocalDate fromDate, LocalDate toDate, int workDays,
      Summary summary, List<ActivityDrillDown> activities) {
    this(projectId, supervisorUserId, supervisorName, fromDate, toDate, workDays,
        null, summary, activities);
  }

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

  /** Numeric snapshot for one bucket of a trade or equipment rollup. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PeriodMetrics(
      BigDecimal qty,                       // allocated qty for the bucket
      BigDecimal budgetedDays,              // qty / norm summed over the bucket
      BigDecimal actualDays,                // Σ nos in the bucket (includes tracked + suppressed + untracked)
      BigDecimal actualDaysOnHiddenSides,   // nos on (DPR, activity) where this side was suppressed
      BigDecimal actualDaysUntracked,       // nos on activities where the role's norm didn't resolve
      BigDecimal utilizationPct,            // (budgetedDays / trackedDays) × 100, capped 999
      BigDecimal costImplication            // (trackedDays - budgetedDays) × rate
  ) {}

  /** Three-bucket bundle: Day / CalendarMonth / Cumulative. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PeriodMetricsBuckets(
      PeriodMetrics day,
      PeriodMetrics calendarMonth,
      PeriodMetrics cumulative
  ) {}

  /** Three-bucket bundle for activity drill-down {@link PlannedActuals}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PlannedActualsBuckets(
      PlannedActuals day,
      PlannedActuals calendarMonth,
      PlannedActuals cumulative
  ) {}

  /** One row of the SC180 "Manpower Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TradeRollup(
      String tradeKey,            // canonical: ResourceRole.code or UPPER(TRIM(dpr_manpower.trade))
      String tradeLabel,          // ResourceRole.name or raw trade text
      BigDecimal mmRate,          // Σ(line_cost) / Σ(actualManDays)
      BigDecimal qtyDone,         // ALLOCATED qty for this trade across the cumulative window (per-DPR allocator share, not raw DPR qty)
      BigDecimal budgetedManDays, // Σ over (DPR,activity) (allocatedQty / output_per_man_per_day) — cumulative window
      BigDecimal actualManDays,   // Σ(nos) cumulative window — raw headcount-days, hours ignored. Includes tracked + suppressed + untracked.
      /** Portion of {@link #actualManDays} on (DPR, activity) where this trade's manpower side was
       *  suppressed by the allocator (SERIES/SUBSTITUTE governed by equipment). Norm exists but
       *  this side didn't drive output. Null when zero. Cumulative-window total. */
      BigDecimal actualDaysOnHiddenSides,
      /** Portion of {@link #actualManDays} where the trade's norm didn't resolve for the activity.
       *  Null when zero. Cumulative-window total. */
      BigDecimal actualDaysUntracked,
      BigDecimal utilizationPct,  // (budgetedManDays / trackedManDays) × 100, capped 999 — cumulative window
      BigDecimal costImplication, // (trackedManDays - budgetedManDays) × mmRate — cumulative window
      String normSource,          // SPECIFIC_RESOURCE | RESOURCE_TYPE | RESOURCE_LEGACY | NONE
      /** Optional Day / CalendarMonth / Cumulative breakdown. Null on legacy responses
       *  (pre-2026-05-25). When non-null, {@code buckets.cumulative} matches the flat fields
       *  above. The day and calendarMonth slices are anchored on
       *  {@link SupervisorPerformanceReport#referenceDate}. */
      PeriodMetricsBuckets buckets
  ) {
    /** Back-compat ctor — older callers can omit hidden/untracked breakdown. */
    public TradeRollup(String tradeKey, String tradeLabel, BigDecimal mmRate, BigDecimal qtyDone,
                       BigDecimal budgetedManDays, BigDecimal actualManDays,
                       BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(tradeKey, tradeLabel, mmRate, qtyDone, budgetedManDays, actualManDays,
          null, null, utilizationPct, costImplication, normSource, null);
    }

    /** Back-compat ctor — older callers can omit buckets. */
    public TradeRollup(String tradeKey, String tradeLabel, BigDecimal mmRate, BigDecimal qtyDone,
                       BigDecimal budgetedManDays, BigDecimal actualManDays,
                       BigDecimal actualDaysOnHiddenSides, BigDecimal actualDaysUntracked,
                       BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(tradeKey, tradeLabel, mmRate, qtyDone, budgetedManDays, actualManDays,
          actualDaysOnHiddenSides, actualDaysUntracked, utilizationPct, costImplication,
          normSource, null);
    }
  }

  /** One row of the SC180 "Equipment Utilization" table. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EquipmentRollup(
      String equipmentKey,
      String equipmentLabel,
      BigDecimal hourRate,        // Σ(line_cost) / Σ(actualDays)
      BigDecimal qtyDone,         // ALLOCATED qty cumulative window (per-DPR allocator share)
      BigDecimal budgetedDays,    // cumulative window
      BigDecimal actualDays,      // Σ(nos) cumulative window — raw equipment-days, hours ignored. Includes tracked + suppressed + untracked.
      /** See {@link TradeRollup#actualDaysOnHiddenSides}. Cumulative-window total. */
      BigDecimal actualDaysOnHiddenSides,
      /** See {@link TradeRollup#actualDaysUntracked}. Cumulative-window total. */
      BigDecimal actualDaysUntracked,
      BigDecimal utilizationPct,  // cumulative window
      BigDecimal costImplication, // cumulative window
      String normSource,
      /** Optional Day / CalendarMonth / Cumulative breakdown. See {@link TradeRollup#buckets}. */
      PeriodMetricsBuckets buckets
  ) {
    /** Back-compat ctor — older callers can omit hidden/untracked breakdown. */
    public EquipmentRollup(String equipmentKey, String equipmentLabel, BigDecimal hourRate,
                           BigDecimal qtyDone, BigDecimal budgetedDays, BigDecimal actualDays,
                           BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(equipmentKey, equipmentLabel, hourRate, qtyDone, budgetedDays, actualDays,
          null, null, utilizationPct, costImplication, normSource, null);
    }

    /** Back-compat ctor — older callers can omit buckets. */
    public EquipmentRollup(String equipmentKey, String equipmentLabel, BigDecimal hourRate,
                           BigDecimal qtyDone, BigDecimal budgetedDays, BigDecimal actualDays,
                           BigDecimal actualDaysOnHiddenSides, BigDecimal actualDaysUntracked,
                           BigDecimal utilizationPct, BigDecimal costImplication, String normSource) {
      this(equipmentKey, equipmentLabel, hourRate, qtyDone, budgetedDays, actualDays,
          actualDaysOnHiddenSides, actualDaysUntracked, utilizationPct, costImplication,
          normSource, null);
    }
  }

  /** Per-activity drill-down — mirrors the SC180 "PR for MP and Eqt" sheet. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivityDrillDown(
      UUID activityId,
      String activityCode,
      String activityName,
      String unit,
      /** Total activity output for the cumulative window — Σ dpr.qty_executed across DPRs.
       *  Includes sub-contractor share. The frontend pairs this with {@link #subContractorQty}
       *  to show "200 Nos (170 own + 30 sub-contractor)". The "Month" suffix is a misnomer;
       *  this is cumulative-window. See {@link #qtyForCalendarMonth} for the actual calendar
       *  month slice and {@link #qtyForDay} for the single-day anchor slice. */
      BigDecimal qtyForMonth,
      /** Σ dpr.qty_executed restricted to {@link SupervisorPerformanceReport#referenceDate}.
       *  Null on legacy responses (pre-2026-05-25). */
      BigDecimal qtyForDay,
      /** Σ dpr.qty_executed restricted to the calendar month of
       *  {@link SupervisorPerformanceReport#referenceDate}. Null on legacy responses. */
      BigDecimal qtyForCalendarMonth,
      /** Σ dpr_sub_contractor.quantity across DPRs in the window. Null when no sub-contractor
       *  rows exist (frontend then renders just the total qty without the breakdown).
       *  Cumulative-window total. */
      BigDecimal subContractorQty,
      List<ResourceLine> resources,
      String remarks) {
    /** Back-compat ctor — older callers that don't pass subContractorQty + buckets. */
    public ActivityDrillDown(UUID activityId, String activityCode, String activityName,
                             String unit, BigDecimal qtyForMonth,
                             List<ResourceLine> resources, String remarks) {
      this(activityId, activityCode, activityName, unit, qtyForMonth, null, null, null,
          resources, remarks);
    }

    /** Back-compat ctor — older callers that don't pass day/calendarMonth qty buckets. */
    public ActivityDrillDown(UUID activityId, String activityCode, String activityName,
                             String unit, BigDecimal qtyForMonth, BigDecimal subContractorQty,
                             List<ResourceLine> resources, String remarks) {
      this(activityId, activityCode, activityName, unit, qtyForMonth, null, null,
          subContractorQty, resources, remarks);
    }
  }

  /** One row inside an {@link ActivityDrillDown}: either a manpower trade or an equipment type. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResourceLine(
      String kind,                // MANPOWER | EQUIPMENT
      String resourceKey,
      String resourceLabel,
      ProductivityNorms norms,
      /** Cumulative-window plan (misnomer — historical name). */
      PlannedActuals planMonth,
      /** Cumulative-window actuals (misnomer — historical name). */
      PlannedActuals actualMonth,
      /** Optional Day / CalendarMonth / Cumulative breakdown of plan. Null on legacy responses. */
      PlannedActualsBuckets planBuckets,
      /** Optional Day / CalendarMonth / Cumulative breakdown of actuals. Null on legacy. */
      PlannedActualsBuckets actualBuckets) {
    /** Back-compat ctor — older callers without bucket data. */
    public ResourceLine(String kind, String resourceKey, String resourceLabel,
                        ProductivityNorms norms, PlannedActuals planMonth,
                        PlannedActuals actualMonth) {
      this(kind, resourceKey, resourceLabel, norms, planMonth, actualMonth, null, null);
    }
  }

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
