package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ResourceAssignment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ResourceAssignmentResponse(
    UUID id,
    UUID activityId,
    String activityName,
    UUID resourceId,
    String resourceName,
    UUID roleId,
    String roleName,
    UUID effectiveRoleId,
    String effectiveRoleName,
    /** Productivity unit of the effective role (e.g. "Day", "Bag", "Nos") — used by the UI to
     * decide whether activity-level rollups can sum units (only when all assignments share it). */
    String unit,
    UUID projectId,
    /** Phase 2: original committed units, frozen unless an explicit Re-budget action runs. */
    Double budgetedUnits,
    /** Phase 2: original committed cost, frozen unless an explicit Re-budget action runs. */
    BigDecimal budgetedCost,
    Double plannedUnits,
    /** Raw nos the planner entered for manpower/equipment. {@link #plannedUnits} keeps the
     *  person-day product (headcount × duration) for DPR/EVA rollups; this field exposes the
     *  human-meaningful "nos" so UI rollups (e.g. Resource Plan) can display the entered value. */
    Integer headcount,
    /** Activity duration applied when computing {@link #plannedUnits}. Exposed for UI tooltips. */
    BigDecimal duration,
    /** Raw quantity the planner entered for material assignments. */
    BigDecimal quantity,
    Double actualUnits,
    Double remainingUnits,
    Double atCompletionUnits,
    BigDecimal plannedCost,
    BigDecimal actualCost,
    BigDecimal remainingCost,
    BigDecimal atCompletionCost,
    String rateType,
    UUID resourceCurveId,
    LocalDate plannedStartDate,
    LocalDate plannedFinishDate,
    LocalDate actualStartDate,
    LocalDate actualFinishDate,
    boolean staffed,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy) {

  /** Legacy constructor — names null. */
  public static ResourceAssignmentResponse from(ResourceAssignment assignment) {
    return from(assignment, null, null, null, null, null, null);
  }

  public static ResourceAssignmentResponse from(
      ResourceAssignment assignment,
      String resourceName,
      String activityName,
      String roleName,
      UUID effectiveRoleId,
      String effectiveRoleName,
      String unit) {
    return new ResourceAssignmentResponse(
        assignment.getId(),
        assignment.getActivityId(),
        activityName,
        assignment.getResourceId(),
        resourceName,
        assignment.getRoleId(),
        roleName,
        effectiveRoleId,
        effectiveRoleName,
        unit,
        assignment.getProjectId(),
        assignment.getBudgetedUnits(),
        assignment.getBudgetedCost(),
        assignment.getPlannedUnits(),
        assignment.getHeadcount(),
        assignment.getDuration(),
        assignment.getQuantity(),
        assignment.getActualUnits(),
        assignment.getRemainingUnits(),
        assignment.getAtCompletionUnits(),
        assignment.getPlannedCost(),
        assignment.getActualCost(),
        assignment.getRemainingCost(),
        assignment.getAtCompletionCost(),
        assignment.getRateType(),
        assignment.getResourceCurveId(),
        assignment.getPlannedStartDate(),
        assignment.getPlannedFinishDate(),
        assignment.getActualStartDate(),
        assignment.getActualFinishDate(),
        assignment.getResourceId() != null,
        assignment.getCreatedAt(),
        assignment.getUpdatedAt(),
        assignment.getCreatedBy(),
        assignment.getUpdatedBy());
  }
}
