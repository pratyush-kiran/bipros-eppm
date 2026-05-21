package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record EquipmentRoleVariantRequest(
    @NotBlank String make,
    @NotBlank String model,
    @NotBlank String unit,
    @NotNull @Positive BigDecimal rate,
    BigDecimal standardOutputPerDay,
    Boolean active) {}
