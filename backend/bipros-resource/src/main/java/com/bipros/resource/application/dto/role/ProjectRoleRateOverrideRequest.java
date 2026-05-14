package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** Polymorphic override request — set exactly one of the three variant IDs. */
public record ProjectRoleRateOverrideRequest(
    UUID manpowerRoleRateId,
    UUID equipmentRoleVariantId,
    UUID materialRoleVariantId,
    @NotNull @Positive BigDecimal overrideRate,
    Boolean active) {}
