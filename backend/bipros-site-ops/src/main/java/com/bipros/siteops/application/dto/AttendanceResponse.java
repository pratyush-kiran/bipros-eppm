package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SkillCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceResponse(
        UUID id,
        UUID projectId,
        LocalDate date,
        String contractorName,
        SkillCategory skillCategory,
        Integer plannedCount,
        Integer actualCount,
        BigDecimal hoursWorked,
        String notes,
        UUID approvedBy,
        Instant approvedAt,
        UUID submittedBy,
        Instant submittedAt,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
