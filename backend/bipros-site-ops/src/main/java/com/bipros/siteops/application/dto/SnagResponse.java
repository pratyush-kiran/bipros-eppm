package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SnagSeverity;
import com.bipros.siteops.domain.model.SnagStatus;

import java.time.Instant;
import java.util.UUID;

public record SnagResponse(
        UUID id,
        UUID projectId,
        UUID activityId,
        String locationCode,
        String description,
        SnagSeverity severity,
        SnagStatus status,
        UUID raisedBy,
        Instant raisedAt,
        UUID closedBy,
        Instant closedAt,
        String closureNote,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
