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
      RolePeriod totalCumulative) {}

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
      BigDecimal utilizationPct,
      BigDecimal costImplication,
      /**
       * Of the {@link #actualDays}, how many were on SERIES-configured activities where this
       * role's side was NOT the governing one — i.e. the other side's norm capped expected
       * output, so this role was inherently constrained, not underperforming. Null when no
       * such days exist. Used by the UI to surface a "constrained by [side] bottleneck"
       * annotation explaining why the util% is otherwise low.
       */
      BigDecimal constrainedDays,
      /** {@code MANPOWER} | {@code EQUIPMENT} — which side governed (capped the output) on the
       *  constrained activities. Null when {@link #constrainedDays} is null. */
      String constrainedBySide
  ) {
    public static RolePeriod empty() {
      return new RolePeriod(null, null, null, null, null, null, null, null, null, null, null, null);
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
