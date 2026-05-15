package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.WorkActivity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductivityNormResponse(
    UUID id,
    ProductivityNormType normType,

    UUID workActivityId,
    String workActivityName,
    String workActivityCode,

    UUID resourceTypeId,
    String resourceTypeName,

    UUID resourceId,
    String resourceCode,
    String resourceName,

    @Schema(deprecated = true, description = "Deprecated echo of the legacy free-text activity label.")
    @Deprecated
    String activityName,

    String unit,
    BigDecimal outputPerManPerDay,
    BigDecimal outputPerHour,
    Integer crewSize,
    BigDecimal outputPerDay,
    Double workingHoursPerDay,
    BigDecimal fuelLitresPerHour,
    String equipmentSpec,
    String remarks,

    /** Role-keyed scope. Optional — null on legacy Type/Resource/Unscoped rows. */
    UUID roleId,
    UUID categoryId,
    UUID gradeId,
    String make,
    String model
) {
  public static ProductivityNormResponse from(ProductivityNorm norm) {
    WorkActivity wa = norm.getWorkActivity();
    ResourceType type = norm.getResourceType();
    Resource res = norm.getResource();
    return new ProductivityNormResponse(
        norm.getId(),
        norm.getNormType(),
        wa == null ? null : wa.getId(),
        wa == null ? null : wa.getName(),
        wa == null ? null : wa.getCode(),
        type == null ? null : type.getId(),
        type == null ? null : type.getName(),
        res == null ? null : res.getId(),
        res == null ? null : res.getCode(),
        res == null ? null : res.getName(),
        norm.getActivityName(),
        norm.getUnit(),
        norm.getOutputPerManPerDay(),
        norm.getOutputPerHour(),
        norm.getCrewSize(),
        norm.getOutputPerDay(),
        norm.getWorkingHoursPerDay(),
        norm.getFuelLitresPerHour(),
        norm.getEquipmentSpec(),
        norm.getRemarks(),
        norm.getRoleId(),
        norm.getCategoryId(),
        norm.getGradeId(),
        norm.getMake(),
        norm.getModel()
    );
  }
}
