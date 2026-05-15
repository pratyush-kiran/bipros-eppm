package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.Shift;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShiftHandoverResponse(
        UUID id,
        UUID projectId,
        LocalDate shiftDate,
        Shift shift,
        UUID fromUserId,
        UUID toUserId,
        String summary,
        String pendingItems,
        Instant handedOverAt,
        Instant acknowledgedAt,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
