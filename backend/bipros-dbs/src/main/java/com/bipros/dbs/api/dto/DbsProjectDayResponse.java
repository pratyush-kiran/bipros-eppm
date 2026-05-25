package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Single-day DBS payload at project level. {@code cumulative*} fields are computed at read
 * time by summing all dbs_daily_project rows with {@code report_date &lt;= this date}; they
 * are not persisted because they would invalidate on any historical recompute.
 *
 * <p>Phase 7: {@code directCost} / {@code prelimCost} / {@code totalCostInclPrelims} /
 * {@code pctAchieved} carry the project-wide prelim split and progress KPI.
 *
 * <p>Section G: {@code generalExpenseAmount} is the daily-prorated value
 * ({@code monthlyTotal / daysInMonth}). {@code generalExpenseMonthlyTotal} carries
 * the raw month total for the UI, and {@code generalExpenseLinesJson} is the
 * per-item accordion payload.
 */
public record DbsProjectDayResponse(
    UUID id,
    UUID projectId,
    LocalDate reportDate,
    List<UUID> engineerIds,
    Integer supervisorCount,
    Integer dprCount,
    BigDecimal materialAmount,
    BigDecimal manpowerAmount,
    BigDecimal adminAmount,
    BigDecimal machineryAmount,
    BigDecimal fuelAmount,
    BigDecimal subcontractAmount,
    BigDecimal generalExpenseAmount,
    BigDecimal generalExpenseMonthlyTotal,
    String generalExpenseLinesJson,
    BigDecimal boqForTheDayAmount,
    BigDecimal boqPlannedAmount,
    BigDecimal boqAchievedAmount,
    BigDecimal directCost,
    BigDecimal prelimCost,
    BigDecimal totalCostInclPrelims,
    BigDecimal pctAchieved,
    BigDecimal totalExpense,
    BigDecimal totalIncome,
    BigDecimal contribution,
    BigDecimal contributionPct,
    BigDecimal cumulativeExpense,
    BigDecimal cumulativeIncome,
    BigDecimal cumulativeContribution,
    List<DbsSubContractLineDto> subcontractLines,
    Instant recomputedAt,
    List<String> alerts
) {}
