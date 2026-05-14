package com.bipros.resource.application.dto.role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RoleAssignmentResponse(
    UUID id,
    UUID activityId,
    String activityName,
    UUID projectId,
    UUID roleId,
    String roleName,
    String roleType,
    UUID variantId,
    String variantLabel,
    Integer headcount,
    BigDecimal duration,
    BigDecimal quantity,
    BigDecimal plannedUnits,
    BigDecimal actualUnits,
    BigDecimal remainingUnits,
    BigDecimal plannedCost,
    BigDecimal actualCost,
    BigDecimal remainingCost,
    BigDecimal effectiveRate,
    String unit,
    String rateType,
    LocalDate plannedStartDate,
    LocalDate plannedFinishDate) {}
