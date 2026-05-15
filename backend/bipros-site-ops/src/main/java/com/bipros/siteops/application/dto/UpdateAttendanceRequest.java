package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SkillCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateAttendanceRequest(
        LocalDate date,
        String contractorName,
        SkillCategory skillCategory,
        @Min(0) Integer plannedCount,
        @Min(0) Integer actualCount,
        @DecimalMin("0.0") BigDecimal hoursWorked,
        String notes
) {
}
