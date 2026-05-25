package com.bipros.resource.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record SubContractorWorkActivityMappingRow(
    UUID id,

    @NotNull(message = "scWorkTypeId is required")
    UUID scWorkTypeId,

    String workTypeName,

    String unit,

    @PositiveOrZero(message = "ratePerUnit must be zero or positive")
    BigDecimal ratePerUnit,

    @PositiveOrZero(message = "outputPerDay must be zero or positive")
    BigDecimal outputPerDay
) {
}
