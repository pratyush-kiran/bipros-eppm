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
    BigDecimal boqForTheDayAmount,
    BigDecimal boqPlannedAmount,
    BigDecimal boqAchievedAmount,
    BigDecimal totalExpense,
    BigDecimal totalIncome,
    BigDecimal contribution,
    BigDecimal contributionPct,
    BigDecimal cumulativeExpense,
    BigDecimal cumulativeIncome,
    BigDecimal cumulativeContribution,
    Instant recomputedAt,
    List<String> alerts
) {}
