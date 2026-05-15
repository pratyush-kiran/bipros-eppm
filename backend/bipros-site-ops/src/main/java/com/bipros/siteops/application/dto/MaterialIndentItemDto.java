package com.bipros.siteops.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record MaterialIndentItemDto(
        UUID id,
        @NotBlank String materialName,
        @NotNull BigDecimal quantity,
        @NotBlank String uom,
        String remarks
) {}
