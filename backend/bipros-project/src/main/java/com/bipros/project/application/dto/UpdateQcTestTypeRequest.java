package com.bipros.project.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateQcTestTypeRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 30) String unit,
    @DecimalMin("0.0000") @DecimalMax("999999999.9999") BigDecimal ircThreshold
) {}
