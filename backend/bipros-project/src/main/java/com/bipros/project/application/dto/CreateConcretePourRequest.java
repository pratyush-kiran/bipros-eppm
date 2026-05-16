package com.bipros.project.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateConcretePourRequest(
    @NotNull LocalDate pourDate,

    @NotBlank String site,

    String plantName,

    @PositiveOrZero Long chainageM,

    @NotBlank String structure,

    String element,

    String gradeCode,

    @NotNull @Positive BigDecimal quantityM3,

    BigDecimal slumpValue,

    BigDecimal temperatureC,

    String sectionLabel,

    UUID supervisorUserId,

    UUID dprId,

    String remarks
) {}
