package com.bipros.cost.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * BOQ-item-level margin row used by the budgeted-rate and BOQ-rate P&L views.
 * {@code rate} carries whichever rate the parent view is keyed on (budgetedRate or boqRate).
 */
public record MarginItemDto(
        UUID boqItemId,
        String itemNo,
        String description,
        String unit,
        BigDecimal qtyExecuted,
        BigDecimal rate,
        BigDecimal revenue,
        BigDecimal actualCost,
        BigDecimal margin,
        BigDecimal marginPct
) {}
