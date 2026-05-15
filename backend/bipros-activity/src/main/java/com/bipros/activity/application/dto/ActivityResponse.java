package com.bipros.activity.application.dto;

import com.bipros.activity.domain.model.*;

import java.time.Instant;
import java.time.LocalDate;
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
    // Deprecated Phase 4.5: dropped from the OLTP store by Liquibase 094. Always null
    // for read-back; new clients must use the supervisor user id (resolved through the
    // supervisor endpoint or a join via public.users).
    UUID responsibleResourceId,
    // Deprecated Phase 4.5: dropped from the OLTP store by Liquibase 094. Always null
    // for read-back; resolve the supervisor's display name from the user profile keyed by
    // supervisor_user_id.
    String responsibleResourceName,
    // Phase 4.5 supervisor — User FK to public.users.id. Null when unassigned.
    UUID supervisorUserId,
    // Display-snapshot of the supervisor's name at assignment time.
    String supervisorUserName,
    /** Mirror of {@code resource.work_activities.default_unit} for the linked WorkActivity.
     *  Lets the DPR form auto-fill {@code DPR.unit} when an activity is picked. Null when the
     *  activity has no work-activity link, or when the list path didn't bulk-load default units. */
    String workActivityDefaultUnit,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy
) {
  public static ActivityResponse from(Activity activity) {
    return from(activity, (String) null);
  }

  /** Overload used by list paths that bulk-fetch {@code WorkActivity.default_unit}. */
  public static ActivityResponse from(Activity activity, String workActivityDefaultUnit) {
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
        activity.getSupervisorUserId(),
        activity.getSupervisorUserName(),
        workActivityDefaultUnit,
        activity.getCreatedAt(),
        activity.getUpdatedAt(),
        activity.getCreatedBy(),
        activity.getUpdatedBy()
    );
  }

  /**
   * Overlay-compatible factory: computes DURATION / UNITS percent on read so the
   * detail page always shows a freshly derived value. PHYSICAL activities pass through
   * unchanged. Only the {@code percentComplete} field is recomputed; the intermediate
   * columns (physicalPercentComplete, durationPercentComplete, unitsPercentComplete)
   * still come from the persisted row.
   *
   * <p>List endpoints should use the simpler {@link #from(Activity)} for performance —
   * the nightly job keeps those eventually consistent.
   */
  public static ActivityResponse from(Activity activity,
      com.bipros.activity.application.percent.PercentCompleteCalculator calculator,
      java.time.LocalDate statusDate) {
    var result = calculator.calculate(activity, null, null, statusDate);
    double pct = result.isKeepPrior() ? activity.getPercentComplete() : result.percent();
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
        result.isKeepPrior() ? activity.getStatus() : (result.status() != null ? result.status() : activity.getStatus()),
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
        result.forcedActualFinish() != null ? result.forcedActualFinish() : activity.getActualFinishDate(),
        activity.getTotalFloat(),
        activity.getFreeFloat(),
        pct,
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
        activity.getSupervisorUserId(),
        activity.getSupervisorUserName(),
        null,
        activity.getCreatedAt(),
        activity.getUpdatedAt(),
        activity.getCreatedBy(),
        activity.getUpdatedBy()
    );
  }
}
