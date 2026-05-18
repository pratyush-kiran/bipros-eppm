package com.bipros.project.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the Daily Cost Report (Section B of the Excel "Daily Cost Report" sheet).
 * All cost / variance fields are derived server-side by the {@code DailyCostReportService};
 * clients should not recompute.
 */
public record DailyCostReportRow(
    UUID dprId,
    LocalDate date,
    String activity,
    BigDecimal qtyExecuted,
    String unit,
    UUID boqItemId,
    String boqItemNo,
    BigDecimal budgetedUnitRate,
    BigDecimal actualUnitRate,
    BigDecimal budgetedCost,
    BigDecimal actualCost,
    BigDecimal variance,
    BigDecimal variancePercent,
    /**
     * Estimate to Complete projected onto this row from the activity's most recent
     * {@code EvmCalculation}, proportional to the row's share of the activity's total actual
     * cost. {@code null} when no EvmCalculation exists for the activity or when the share
     * cannot be computed (zero activity actual).
     */
    BigDecimal etc,
    /** Estimate at Completion projected onto this row — see {@link #etc} for the share math. */
    BigDecimal eac,
    String supervisor
) {}
