package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SkillCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateAttendanceRequest(
        @NotNull LocalDate date,
        @NotBlank String contractorName,
        @NotNull SkillCategory skillCategory,
        @NotNull @Min(0) Integer plannedCount,
        @NotNull @Min(0) Integer actualCount,
        @NotNull @DecimalMin("0.0") BigDecimal hoursWorked,
        String notes
) {
}
