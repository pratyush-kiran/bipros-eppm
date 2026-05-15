package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ProductivityNormType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductivityNormRequest(
    @NotNull(message = "normType is required")
    ProductivityNormType normType,

    /**
     * Master activity this norm applies to. Required when {@link #activityName()} is omitted; if
     * both are supplied, {@code workActivityId} wins. The service resolves a missing
     * {@code workActivityId} from {@code activityName} (case-insensitive) for back-compat.
     */
    UUID workActivityId,

    /** Type-level scope: norm applies to every resource of this type. */
    UUID resourceTypeId,

    /** Override scope: norm applies only to this specific resource. */
    UUID resourceId,

    /**
     * Legacy free-text activity name. Optional now — provide {@link #workActivityId()} for new
     * integrations. Will be removed in a future release.
     */
    @Schema(deprecated = true,
        description = "Deprecated: use workActivityId instead.")
    @Deprecated
    String activityName,

    @NotBlank(message = "unit is required")
    String unit,

    @PositiveOrZero BigDecimal outputPerManPerDay,

    @PositiveOrZero BigDecimal outputPerHour,

    @PositiveOrZero Integer crewSize,

    @PositiveOrZero BigDecimal outputPerDay,

    @PositiveOrZero Double workingHoursPerDay,

    @PositiveOrZero BigDecimal fuelLitresPerHour,

    String equipmentSpec,

    String remarks,

    /**
     * Role-keyed scope (new model). When set, the norm applies to a role (and optionally a
     * variant). Mutually exclusive with {@link #resourceTypeId()} / {@link #resourceId()}.
     * Resolution: variant ({@link #roleId} + {@link #categoryId}/{@link #gradeId} or
     * {@link #make}/{@link #model}) → role-only → unscoped.
     */
    UUID roleId,
    UUID categoryId,
    UUID gradeId,
    String make,
    String model
) {}
