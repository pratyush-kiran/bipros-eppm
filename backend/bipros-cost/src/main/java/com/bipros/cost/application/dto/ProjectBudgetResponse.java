package com.bipros.cost.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProjectBudgetResponse(
    BigDecimal originalBudget,
    BigDecimal currentBudget,
    BigDecimal pendingAdditions,
    BigDecimal pendingReductions,
    BigDecimal approvedAdditions,
    BigDecimal approvedReductions,
    int pendingChangeCount,
    int approvedChangeCount,
    String budgetCurrency,
    Instant budgetUpdatedAt
) {
}
