package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SnagSeverity;
import com.bipros.siteops.domain.model.SnagStatus;

import java.util.UUID;

public record UpdateSnagRequest(
        UUID activityId,
        String locationCode,
        String description,
        SnagSeverity severity,
        SnagStatus status
) {
}
