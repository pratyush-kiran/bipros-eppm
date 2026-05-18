package com.bipros.reporting.materialconsumption;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Top-level response for the Material Consumption Report. {@code totals} holds global
 * sums (plannedCost / actualCost / variance / weighted average wastagePercent); {@code
 * alertCounts} is keyed by alert code (see {@link MaterialConsumptionAlertEvaluator}).
 */
public record MaterialConsumptionReportResponse(
    LocalDate from,
    LocalDate to,
    String groupBy,
    List<MaterialConsumptionRow> rows,
    Map<String, BigDecimal> totals,
    Map<String, Integer> alertCounts) {}
