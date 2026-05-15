package com.bipros.reporting.application.dto;

import com.bipros.reporting.application.dto.SupervisorPerformanceReport.EquipmentRollup;
import com.bipros.reporting.application.dto.SupervisorPerformanceReport.TradeRollup;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Side-by-side comparison of N supervisors over the same window. Carries each supervisor's full
 * {@link SupervisorPerformanceReport} plus pre-pivoted deltas for each trade / equipment so the
 * UI can render columns without re-pivoting client-side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorPerformanceComparison(
    UUID projectId,
    LocalDate fromDate,
    LocalDate toDate,
    int workDays,
    List<SupervisorPerformanceReport> reports,
    List<TradeDelta> tradeDeltas,
    List<EquipmentDelta> equipmentDeltas) {

  /** One row per canonical trade key, with each supervisor's rollup keyed by supervisorUserId. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TradeDelta(
      String tradeKey,
      String tradeLabel,
      Map<UUID, TradeRollup> bySupervisor,
      BigDecimal bestUtilizationPct,
      UUID bestSupervisorId) {}

  /** Same shape, equipment side. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EquipmentDelta(
      String equipmentKey,
      String equipmentLabel,
      Map<UUID, EquipmentRollup> bySupervisor,
      BigDecimal bestUtilizationPct,
      UUID bestSupervisorId) {}
}
