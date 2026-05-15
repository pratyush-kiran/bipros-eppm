package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SkillCategory;

import java.math.BigDecimal;

public record AttendanceSummary(
        SkillCategory skillCategory,
        long totalPlanned,
        long totalActual,
        BigDecimal totalHoursWorked,
        long rowCount
) {
}
