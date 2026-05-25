package com.bipros.reporting.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Capacity Utilization report — SC180-style. Two sections (Manpower + Equipment), each with one
 * row per Role rolled up across the entire project for the selected period. Three time buckets
 * per row: For the Day · For the Month · Cumulative.
 *
 * <p>Carries a legacy flat {@link #rows} list synthesised from the same data so existing
 * consumers (Excel writer, Insights collector) keep working until they're migrated to the
 * new section/role shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapacityUtilizationReport(
    UUID projectId,
    LocalDate fromDate,
    LocalDate toDate,
    int workDays,
    Section manpower,
    Section equipment,
    /** Synthesised for legacy consumers (Excel writer / Insights). Prefer {@link #manpower()} / {@link #equipment()}. */
    String groupBy,
    /** Synthesised for legacy consumers. Prefer {@link #manpower()} / {@link #equipment()}. */
    String normType,
    /** Synthesised for legacy consumers. Prefer {@link #manpower()} / {@link #equipment()}. */
    List<Row> rows
) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Section(
      List<RoleRow> rows,
      RolePeriod totalForTheDay,
      RolePeriod totalForTheMonth,
      RolePeriod totalCumulative,
      /** Activities where this section's side was hidden by the allocator. May be empty. */
      List<HiddenSideNote> hiddenSideNotes
  ) {
    /** Back-compat constructor — pre-existing call sites that don't yet supply hiddenSideNotes
     *  default to an empty list. */
    public Section(List<RoleRow> rows, RolePeriod totalForTheDay,
                   RolePeriod totalForTheMonth, RolePeriod totalCumulative) {
      this(rows, totalForTheDay, totalForTheMonth, totalCumulative, List.of());
    }
  }

  /**
   * Per-activity annotation for a side that was suppressed in a SERIES/SUBSTITUTE allocation.
   * Frontend renders one banner per note in the section the activity belongs to.
   * Example: when manpower governs activity A under SERIES, the equipment section emits
   * {@code new HiddenSideNote(activityA, "Unclassified structural excavation", "MANPOWER", "SERIES")}.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HiddenSideNote(
      UUID activityId,
      String workActivityName,
      /** {@code MANPOWER} | {@code EQUIPMENT} — the side that DID govern (won). */
      String governingSide,
      /** {@code SERIES} | {@code SUBSTITUTE} — the mode that triggered the hiding. */
      String mode
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RoleRow(
      UUID roleId,
      String roleCode,
      String roleName,
      BigDecimal ratePerDay,
      RolePeriod forTheDay,
      RolePeriod forTheMonth,
      RolePeriod cumulative,
      /** {@code VARIANT|ROLE|UNSCOPED|MIXED|NONE}. */
      String normSource
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RolePeriod(
      BigDecimal qty,
      BigDecimal budgetDays,
      BigDecimal budgetNos,
      BigDecimal plannedDays,
      BigDecimal plannedNos,
      BigDecimal actualDays,
      BigDecimal actualNos,
      /**
       * Of the {@link #actualDays}, how many were on activities whose linked Work Activity has
       * <em>no</em> productivity norm for this role's type. Surfaced as a footer line on the row
       * so the user understands why the util% may be lower than expected when only part of the
       * role's deployment is being measured. Null when all actuals are tracked OR none are.
       */
      BigDecimal actualDaysUntracked,
      /**
       * Of the {@link #actualDays}, how many were on activities where THIS role had a resolvable
       * norm but the allocator suppressed this side (SERIES losing side or SUBSTITUTE redundant
       * side). The role's productivity IS measured for these activities — it just didn't drive
       * the day's output. The frontend renders a "suppressed by other side" note so the user
       * isn't told (falsely) that the activity doesn't track productivity. Null when zero.
       */
      BigDecimal actualDaysOnHiddenSides,
      BigDecimal utilizationPct,
      BigDecimal costImplication,
      /**
       * True when at least one of the role's activities in this period had a resolvable
       * productivity norm. False when every activity the role touched was untracked. The
       * frontend uses this to render a "No norm for this role on this activity" note instead
       * of a budget / efficiency that doesn't exist. Null on legacy / synthesised rows where
       * the dimension isn't meaningful.
       */
      Boolean normResolved,
      /** Deprecated — replaced by per-activity {@link HiddenSideNote}. No longer populated as
       *  of 2026-05-22 — kept nullable for one release for external consumers. */
      BigDecimal constrainedDays,
      /** Deprecated — see {@link #constrainedDays}. */
      String constrainedBySide
  ) {
    public static RolePeriod empty() {
      return new RolePeriod(null, null, null, null, null, null, null, null, null, null,
          null, null, null, null);
    }
  }

  // ─── Legacy shapes (kept so SupervisorPerformance + Excel writer + Insights compile).

  /** @deprecated kept for legacy callers; new CapacityUtilizationReport uses {@link Section}. */
  @Deprecated
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Row(
      GroupKey groupKey,
      WorkActivityRef workActivity,
      Budgeted budgeted,
      Period forTheDay,
      Period forTheMonth,
      Period cumulative
  ) {}

  /** @deprecated kept for legacy callers. */
  @Deprecated
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record GroupKey(
      UUID resourceTypeId,
      UUID resourceId,
      String displayLabel
  ) {}

  /** @deprecated kept for legacy callers. */
  @Deprecated
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkActivityRef(
      UUID id,
      String code,
      String name,
      String defaultUnit
  ) {}

  /** @deprecated SupervisorPerformanceReportService still uses this for its trade/equipment rollups. */
  @Deprecated
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Budgeted(
      BigDecimal outputPerDay,
      String source
  ) {}

  /** @deprecated legacy 5-field period; new Period type is {@link RolePeriod}. */
  @Deprecated
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Period(
      BigDecimal qty,
      BigDecimal budgetedDays,
      BigDecimal actualDays,
      BigDecimal actualOutputPerDay,
      BigDecimal utilizationPct,
      /**
       * Portion of actualDays on activities with no productivity norm for the role's type.
       * Threaded through the legacy shape so the Capacity Utilization Excel writer can render
       * an "Act Days (Untracked)" sub-column without re-querying.
       */
      BigDecimal actualDaysUntracked
  ) {
    public Period(BigDecimal qty, BigDecimal budgetedDays, BigDecimal actualDays,
                  BigDecimal actualOutputPerDay, BigDecimal utilizationPct) {
      this(qty, budgetedDays, actualDays, actualOutputPerDay, utilizationPct, null);
    }
  }
}
