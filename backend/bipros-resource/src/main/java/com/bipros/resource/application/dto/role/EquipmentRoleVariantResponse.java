package com.bipros.resource.application.dto.role;

import java.math.BigDecimal;
import java.util.UUID;

public record EquipmentRoleVariantResponse(
    UUID id,
    UUID roleId,
    String roleName,
    String make,
    String model,
    String unit,
    BigDecimal rate,
    BigDecimal standardOutputPerDay,
    Boolean active) {}
