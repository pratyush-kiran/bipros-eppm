package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ResourceResponse(
    UUID id,
    String code,
    String name,
    String description,
    UUID roleId,
    String roleCode,
    String roleName,
    UUID resourceTypeId,
    String resourceTypeCode,
    String resourceTypeName,
    BigDecimal availability,
    BigDecimal costPerUnit,
    String unit,
    UUID rateMasterId,
    ResourceStatus status,
    UUID calendarId,
    UUID parentId,
    Integer sortOrder,
    Instant createdAt,
    Instant updatedAt,
    EquipmentDetailsDto equipment,
    MaterialDetailsDto material,
    ManpowerDto manpower) {

  public static ResourceResponse from(Resource r) {
    return from(r, null, null, null);
  }

  public static ResourceResponse from(
      Resource r,
      EquipmentDetailsDto equipment,
      MaterialDetailsDto material,
      ManpowerDto manpower) {
    ResourceRole role = r.getRole();
    ResourceType type = r.getResourceType();
    return new ResourceResponse(
        r.getId(),
        r.getCode(),
        r.getName(),
        r.getDescription(),
        role == null ? null : role.getId(),
        role == null ? null : role.getCode(),
        role == null ? null : role.getName(),
        type == null ? null : type.getId(),
        type == null ? null : type.getCode(),
        type == null ? null : type.getName(),
        r.getAvailability(),
        r.getCostPerUnit(),
        r.getUnit(),
        r.getRateMasterId(),
        r.getStatus(),
        r.getCalendarId(),
        r.getParentId(),
        r.getSortOrder(),
        r.getCreatedAt(),
        r.getUpdatedAt(),
        equipment,
        material,
        manpower);
  }
}
