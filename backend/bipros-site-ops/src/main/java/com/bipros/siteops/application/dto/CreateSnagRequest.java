package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SnagSeverity;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateSnagRequest(
        UUID activityId,
        String locationCode,
        @NotBlank String description,
        SnagSeverity severity
) {
}
