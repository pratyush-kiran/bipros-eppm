package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Compact per-CM row for the project's "CMs for the day" list. Powers the PM tab drill
 * into the CM tier.
 */
public record DbsCmSummaryDto(
    UUID cmUserId,
    String cmName,
    Integer supervisorCount,
    BigDecimal directCost,
    BigDecimal prelimCost,
    BigDecimal totalCostInclPrelims,
    BigDecimal contributionPct,
    BigDecimal pctAchieved
) {}
