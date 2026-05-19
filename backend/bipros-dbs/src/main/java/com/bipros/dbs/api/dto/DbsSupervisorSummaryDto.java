package com.bipros.dbs.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Compact per-supervisor row for the project's "supervisors for the day" list. Powers
 * the Engineer/PM tab drill-down — totals only, no per-section lines (the supervisor
 * detail endpoint owns those).
 */
public record DbsSupervisorSummaryDto(
    UUID supervisorUserId,
    String supervisorName,
    BigDecimal totalExpense,
    BigDecimal totalIncome,
    BigDecimal contribution,
    BigDecimal contributionPct,
    BigDecimal directCost,
    BigDecimal prelimCost,
    BigDecimal totalCostInclPrelims,
    BigDecimal pctAchieved,
    Integer dprCount
) {}
