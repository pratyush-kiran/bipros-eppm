package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SafetyKind;
import com.bipros.siteops.domain.model.SafetySeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateSafetyRecordRequest(
        @NotNull SafetyKind kind,
        @NotNull Instant occurredAt,
        String locationCode,
        SafetySeverity severity,
        @NotBlank String description,
        String immediateAction,
        String peopleInvolved
) {}
