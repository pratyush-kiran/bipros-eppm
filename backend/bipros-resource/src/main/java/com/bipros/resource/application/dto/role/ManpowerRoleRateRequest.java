package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record ManpowerRoleRateRequest(
    @NotNull UUID categoryId,
    @NotNull UUID gradeId,
    @NotNull String unit,
    @NotNull @Positive BigDecimal rate,
    Boolean active) {}
