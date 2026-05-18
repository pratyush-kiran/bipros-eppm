package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Single-day DBS payload for one engineer (sum across their supervisors). No line arrays
 * — drill into the supervisor view for the per-line breakdown.
 */
public record DbsEngineerDayResponse(
    UUID id,
    UUID projectId,
    UUID engineerUserId,
    LocalDate reportDate,
    List<UUID> supervisorIds,
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
    Instant recomputedAt
) {}
