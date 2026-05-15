package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.WorkfrontStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkfrontResponse(
        UUID id,
        UUID projectId,
        String wbsCode,
        String locationCode,
        WorkfrontStatus status,
        Instant readyAt,
        UUID releasedBy,
        Instant releasedAt,
        String blockers,
        String notes,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
