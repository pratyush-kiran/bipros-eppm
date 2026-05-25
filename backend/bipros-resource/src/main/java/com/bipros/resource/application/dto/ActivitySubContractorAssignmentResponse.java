package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.master.SubContractorMaster;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ActivitySubContractorAssignmentResponse(
    UUID id,
    UUID activityId,
    UUID projectId,
    UUID subContractorMasterId,
    String subContractorName,
    String subContractorCode,
    String subContractorLocation,
    UUID scWorkTypeId,
    String workTypeName,
    String unit,
    BigDecimal plannedUnits,
    BigDecimal ratePerUnit,
    BigDecimal plannedCost,
    BigDecimal actualUnits,
    BigDecimal actualCost,
    BigDecimal remainingUnits,
    BigDecimal remainingCost,
    Instant createdAt,
    Instant updatedAt) {

  public static ActivitySubContractorAssignmentResponse from(
      ActivitySubContractorAssignment a, SubContractorMaster master) {
    // Preserve nullable planned* in the payload (caller may distinguish "not set" from 0);
    // use coalesced values only for remaining* arithmetic.
    BigDecimal planned = a.getPlannedUnits() != null ? a.getPlannedUnits() : BigDecimal.ZERO;
    BigDecimal actual = a.getActualUnits() != null ? a.getActualUnits() : BigDecimal.ZERO;
    BigDecimal plannedCost = a.getPlannedCost() != null ? a.getPlannedCost() : BigDecimal.ZERO;
    BigDecimal actualCost = a.getActualCost() != null ? a.getActualCost() : BigDecimal.ZERO;
    BigDecimal remainingUnits = planned.subtract(actual).max(BigDecimal.ZERO);
    BigDecimal remainingCost = plannedCost.subtract(actualCost).max(BigDecimal.ZERO);
    return new ActivitySubContractorAssignmentResponse(
        a.getId(),
        a.getActivityId(),
        a.getProjectId(),
        a.getSubContractorMasterId(),
        master == null ? null : master.getName(),
        master == null ? null : master.getCode(),
        master == null ? null : master.getLocation(),
        a.getScWorkTypeId(),
        a.getWorkTypeName(),
        a.getUnit(),
        a.getPlannedUnits(),
        a.getRatePerUnit(),
        a.getPlannedCost(),
        a.getActualUnits(),
        a.getActualCost(),
        remainingUnits,
        remainingCost,
        a.getCreatedAt(),
        a.getUpdatedAt());
  }
}
