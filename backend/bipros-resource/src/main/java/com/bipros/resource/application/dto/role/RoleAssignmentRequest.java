package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Role-based activity demand. The (manpower|equipment|material)RoleRateId picks the variant;
 * {@code plannedUnits = headcount} for manpower/equipment (Option B — duration is descriptive only),
 * {@code quantity} is used for material (no duration concept).
 */
public record RoleAssignmentRequest(
    @NotNull UUID activityId,
    @NotNull UUID roleId,
    UUID manpowerRoleRateId,
    UUID equipmentRoleVariantId,
    UUID materialRoleVariantId,
    Integer headcount,
    BigDecimal duration,
    BigDecimal quantity,
    LocalDate plannedStartDate,
    LocalDate plannedFinishDate,
    String rateType) {}
