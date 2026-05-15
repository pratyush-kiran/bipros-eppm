package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.NcrCategory;
import com.bipros.siteops.domain.model.NcrSeverity;
import com.bipros.siteops.domain.model.NcrStatus;

import java.time.Instant;
import java.util.UUID;

public record NcrResponse(
        UUID id,
        UUID projectId,
        String ncrNo,
        String title,
        String description,
        NcrCategory category,
        NcrSeverity severity,
        NcrStatus status,
        UUID raisedBy,
        Instant raisedAt,
        UUID assignedTo,
        String rootCause,
        String correctiveAction,
        UUID closedBy,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt
) {}
