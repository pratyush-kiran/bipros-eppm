package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.IndentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MaterialIndentResponse(
        UUID id,
        UUID projectId,
        String indentNo,
        UUID requestedBy,
        Instant requestedAt,
        LocalDate requiredBy,
        IndentStatus status,
        String notes,
        UUID decisionBy,
        Instant decidedAt,
        String decisionNote,
        int itemsCount,
        List<MaterialIndentItemDto> items,
        Instant createdAt,
        Instant updatedAt
) {}
