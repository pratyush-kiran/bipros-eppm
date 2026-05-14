package com.bipros.project.application.dto;

import com.bipros.project.domain.model.QcOutcome;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record QcTestItemRow(
    @NotNull UUID testTypeId,
    @Size(max = 50) String sampleRefNo,
    @DecimalMin("0.0000") @DecimalMax("999999999.9999") BigDecimal testResult,
    @DecimalMin("0.0000") @DecimalMax("999999999.9999") BigDecimal requiredIrc,
    @NotNull QcOutcome outcome,
    @Size(max = 150) String labInspector
) {}
