package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SafetyKind;
import com.bipros.siteops.domain.model.SafetySeverity;

import java.time.Instant;
import java.util.UUID;

public record SafetyRecordResponse(
        UUID id,
        UUID projectId,
        SafetyKind kind,
        Instant occurredAt,
        String locationCode,
        SafetySeverity severity,
        String description,
        String immediateAction,
        UUID reportedBy,
        String peopleInvolved,
        Instant createdAt,
        Instant updatedAt
) {}
