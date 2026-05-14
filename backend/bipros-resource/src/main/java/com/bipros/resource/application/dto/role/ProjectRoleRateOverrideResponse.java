package com.bipros.resource.application.dto.role;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectRoleRateOverrideResponse(
    UUID id,
    UUID projectId,
    String roleType,
    UUID variantId,
    String variantLabel,
    BigDecimal overrideRate,
    BigDecimal masterRate,
    Boolean active) {}
