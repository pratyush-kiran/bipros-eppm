package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record MaterialRoleVariantRequest(
    @NotBlank String specGrade,
    @NotBlank String unit,
    @NotNull @Positive BigDecimal rate,
    Boolean active) {}
