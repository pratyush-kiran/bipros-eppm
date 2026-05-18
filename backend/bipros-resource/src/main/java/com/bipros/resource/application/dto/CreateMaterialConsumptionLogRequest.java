package com.bipros.resource.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateMaterialConsumptionLogRequest(
    @NotNull LocalDate logDate,
    UUID resourceId,
    @NotBlank String materialName,
    @NotBlank String unit,
    @NotNull @PositiveOrZero BigDecimal openingStock,
    @NotNull @PositiveOrZero BigDecimal received,
    @NotNull @PositiveOrZero BigDecimal consumed,
    @PositiveOrZero BigDecimal wastagePercent,
    String issuedBy,
    String receivedBy,
    UUID wbsNodeId,
    UUID activityId,
    String remarks,
    UUID issuedByUserId,
    UUID receivedByUserId,
    String enteredByRole
) {}
