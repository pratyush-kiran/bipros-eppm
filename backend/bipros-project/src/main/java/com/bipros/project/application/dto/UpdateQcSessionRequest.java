package com.bipros.project.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateQcSessionRequest(
    @NotNull UUID activityId,
    @NotBlank @Size(max = 150) String activityName,
    @NotNull LocalDate testDate,
    @Size(max = 30) String chainageFrom,
    @Size(max = 30) String chainageTo,
    @NotNull @Valid List<QcTestItemRow> items
) {}
