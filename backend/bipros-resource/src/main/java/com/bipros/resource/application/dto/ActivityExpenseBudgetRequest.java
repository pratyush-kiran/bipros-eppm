package com.bipros.resource.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record ActivityExpenseBudgetRequest(
    @NotNull(message = "categoryId is required") UUID categoryId,

    @NotNull(message = "plannedAmount is required")
    @DecimalMin(value = "0.00", message = "plannedAmount cannot be negative")
    BigDecimal plannedAmount,

    @Size(max = 500, message = "notes must be at most 500 characters")
    String notes
) {}
