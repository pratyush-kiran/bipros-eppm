package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Single-day DBS payload for one Construction Manager (sum across their supervisors).
 * Drill into the supervisor or engineer view for per-line breakdowns.
 *
 * <p>Phase 4: {@code directCost}, {@code prelimCost}, {@code totalCostInclPrelims} and
 * {@code pctAchieved} are present on the wire but populated to zero until Phase 7 wires
 * the prelim split into the supervisor calculators.
 */
public record DbsCmDayResponse(
    UUID id,
    UUID projectId,
    UUID cmUserId,
    LocalDate reportDate,
    List<UUID> siteManagerIds,
    List<UUID> engineerIds,
    Integer supervisorCount,
    BigDecimal materialAmount,
    BigDecimal manpowerAmount,
    BigDecimal adminAmount,
    BigDecimal machineryAmount,
    BigDecimal fuelAmount,
    BigDecimal directCost,
    BigDecimal prelimCost,
    BigDecimal totalCostInclPrelims,
    BigDecimal boqForTheDayAmount,
    BigDecimal boqPlannedToDate,
    BigDecimal boqAchievedToDate,
    BigDecimal contributionPct,
    BigDecimal pctAchieved,
    Instant recomputedAt
) {}
