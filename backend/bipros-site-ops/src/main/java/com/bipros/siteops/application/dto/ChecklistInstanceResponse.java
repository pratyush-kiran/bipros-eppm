package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.ChecklistStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChecklistInstanceResponse(
        UUID id,
        UUID projectId,
        UUID activityId,
        UUID templateId,
        String templateCode,
        String templateName,
        ChecklistStatus status,
        UUID startedBy,
        Instant startedAt,
        Instant submittedAt,
        UUID signedBy,
        Instant signedAt,
        List<ChecklistAnswerDto> answers,
        Instant createdAt,
        Instant updatedAt
) {}
