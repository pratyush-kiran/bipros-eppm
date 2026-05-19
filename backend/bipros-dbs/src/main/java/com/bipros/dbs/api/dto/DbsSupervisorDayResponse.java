package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Single-day DBS payload for one supervisor on one project. The {@code *Lines} fields are
 * the parsed JSON column contents; absent / unparsable JSON yields an empty list rather
 * than null so the UI accordions can iterate unconditionally.
 *
 * <p>The {@code id} is {@code null} for synthetic zero-fill rows (no underlying
 * dbs_daily_supervisor row exists for the requested key).
 *
 * <p>Phase 7: {@code directCost} / {@code prelimCost} / {@code totalCostInclPrelims} /
 * {@code pctAchieved} carry the BOQ-by-activity-type split (direct vs preliminary) and
 * cumulative progress vs plan.
 */
public record DbsSupervisorDayResponse(
    UUID id,
    UUID projectId,
    UUID supervisorUserId,
    String supervisorName,
    UUID engineerUserId,
    LocalDate reportDate,
    BigDecimal materialAmount,
    BigDecimal manpowerAmount,
    BigDecimal adminAmount,
    BigDecimal machineryAmount,
    BigDecimal fuelAmount,
    BigDecimal subcontractAmount,
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
    List<DbsSectionLineDto> materialLines,
    List<DbsSectionLineDto> manpowerLines,
    List<DbsSectionLineDto> adminLines,
    List<DbsSectionLineDto> machineryLines,
    List<DbsSectionLineDto> fuelLines,
    List<DbsSectionLineDto> boqLines,
    List<DbsSectionLineDto> subcontractLines,
    Instant recomputedAt
) {}
