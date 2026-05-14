package com.bipros.resource.application.dto.role;

import java.math.BigDecimal;
import java.util.UUID;

public record ManpowerRoleRateResponse(
    UUID id,
    UUID roleId,
    String roleName,
    UUID categoryId,
    String categoryName,
    UUID gradeId,
    String gradeName,
    String unit,
    BigDecimal rate,
    Boolean active) {}
