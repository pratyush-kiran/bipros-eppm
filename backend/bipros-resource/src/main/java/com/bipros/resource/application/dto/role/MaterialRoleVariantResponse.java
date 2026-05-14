package com.bipros.resource.application.dto.role;

import java.math.BigDecimal;
import java.util.UUID;

public record MaterialRoleVariantResponse(
    UUID id,
    UUID roleId,
    String roleName,
    String specGrade,
    String unit,
    BigDecimal rate,
    Boolean active) {}
