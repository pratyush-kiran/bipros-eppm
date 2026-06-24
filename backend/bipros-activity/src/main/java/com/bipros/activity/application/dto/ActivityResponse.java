package com.bipros.activity.application.dto;

import com.bipros.activity.domain.model.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ActivityResponse(
    UUID id,
    String code,
    String name,
    String description,
    UUID projectId,
    UUID wbsNodeId,
    ActivityType activityType,
    DurationType durationType,
    PercentCompleteType percentCompleteType,
    ActivityStatus status,
    ActivityEditStatus editStatus,
    Double originalDuration,
    Double remainingDuration,
    Double atCompletionDuration,
    LocalDate plannedStartDate,
    LocalDate plannedFinishDate,
    LocalDate earlyStartDate,
    LocalDate earlyFinishDate,
    LocalDate lateStartDate,
    LocalDate lateFinishDate,
    LocalDate actualStartDate,
    LocalDate actualFinishDate,
    Double totalFloat,
    Double freeFloat,
    Double percentComplete,
    Double physicalPercentComplete,
    Double durationPercentComplete,
    Double unitsPercentComplete,
    UUID calendarId,
    Boolean isCritical,
    LocalDate suspendDate,
    LocalDate resumeDate,
    ConstraintType primaryConstraintType,
    LocalDate primaryConstraintDate,
    ConstraintType secondaryConstraintType,
    LocalDate secondaryConstraintDate,
    Integer sortOrder,
    String notes,
    Long chainageFromM,
    Long chainageToM,
    UUID workActivityId,
    UUID costAccountId,
    // Deprecated (Phase 4.5): dropped from the OLTP store by Liquibase 094. Always null
    // for read-back; new clients must use the supervisor user id (resolved through the
    // supervisor endpoint or a join via public.users).
    UUID responsibleResourceId,
    // Deprecated (Phase 4.5): dropped from the OLTP store by Liquibase 094. Always null
    // for read-back; resolve the supervisor's display name from the user profile keyed by
    // supervisor_user_id.
    String responsibleResourceName,
    // Legacy first-supervisor cache (kept in sync with {@code supervisors.get(0)} for one release).
    // New clients MUST read {@code supervisors} instead — these two fields will be removed once
    // every UI surface has migrated.
    UUID supervisorUserId,
    String supervisorUserName,
    /**
     * All supervisors assigned to this activity. The list is "all equal" — there is no
     * primary. Empty when no supervisor is assigned. Populated by the list/get paths;
     * factories that don't have the list available pass {@code null} and the field is
     * serialised as an empty list.
     */
    List<SupervisorEntry> supervisors,
    /** Mirror of {@code resource.work_activities.default_unit} for the linked WorkActivity.
     *  Lets the DPR form auto-fill {@code DPR.unit} when an activity is picked. Null when the
     *  activity has no work-activity link, or when the list path didn't bulk-load default units. */
    String workActivityDefaultUnit,
    /**
     * DBS-Phase-2: when {@code true}, this activity is a BOQ Section 1 Preliminary (mobilisation,
     * site setup, diversions, etc.) and its cost contribution is bucketed into {@code prelim_cost}
     * instead of {@code direct_cost} on every DBS rollup. Mirrors {@link com.bipros.activity.domain.model.Activity#isPreliminary()}.
     */
    boolean preliminary,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy
) {
  public static ActivityResponse from(Activity activity) {
    return from(activity, (String) null, null);
  }

  /** Overload used by list paths that bulk-fetch {@code WorkActivity.default_unit}. */
  public static ActivityResponse from(Activity activity, String workActivityDefaultUnit) {
    return from(activity, workActivityDefaultUnit, null);
  }

  /**
   * Full factory used by list/get paths that bulk-fetch the supervisor list. The legacy
   * singletons {@code supervisorUserId} / {@code supervisorUserName} are derived from the
   * first list entry so older frontends keep working until they migrate.
   */
  public static ActivityResponse from(Activity activity, String workActivityDefaultUnit,
      List<SupervisorEntry> supervisors) {
    List<SupervisorEntry> sups = supervisors == null ? List.of() : supervisors;
    UUID supervisorUserId = sups.isEmpty() ? activity.getSupervisorUserId() : sups.get(0).userId();
    String supervisorUserName =
        sups.isEmpty() ? activity.getSupervisorUserName() : sups.get(0).userName();
    return new ActivityResponse(
        activity.getId(),
        activity.getCode(),
        activity.getName(),
        activity.getDescription(),
        activity.getProjectId(),
        activity.getWbsNodeId(),
        activity.getActivityType(),
        activity.getDurationType(),
        activity.getPercentCompleteType(),
        activity.getStatus(),
        activity.getEditStatus(),
        activity.getOriginalDuration(),
        activity.getRemainingDuration(),
        activity.getAtCompletionDuration(),
        activity.getPlannedStartDate(),
        activity.getPlannedFinishDate(),
        activity.getEarlyStartDate(),
        activity.getEarlyFinishDate(),
        activity.getLateStartDate(),
        activity.getLateFinishDate(),
        activity.getActualStartDate(),
        activity.getActualFinishDate(),
        activity.getTotalFloat(),
        activity.getFreeFloat(),
        activity.getPercentComplete(),
        activity.getPhysicalPercentComplete(),
        activity.getDurationPercentComplete(),
        activity.getUnitsPercentComplete(),
        activity.getCalendarId(),
        activity.getIsCritical(),
        activity.getSuspendDate(),
        activity.getResumeDate(),
        activity.getPrimaryConstraintType(),
        activity.getPrimaryConstraintDate(),
        activity.getSecondaryConstraintType(),
        activity.getSecondaryConstraintDate(),
        activity.getSortOrder(),
        activity.getNotes(),
        activity.getChainageFromM(),
        activity.getChainageToM(),
        activity.getWorkActivityId(),
        activity.getCostAccountId(),
        activity.getResponsibleResourceId(),
        activity.getResponsibleResourceName(),
        supervisorUserId,
        supervisorUserName,
        sups,
        workActivityDefaultUnit,
        activity.isPreliminary(),
        activity.getCreatedAt(),
        activity.getUpdatedAt(),
        activity.getCreatedBy(),
        activity.getUpdatedBy()
    );
  }
}
