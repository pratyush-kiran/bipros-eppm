package com.bipros.activity.application.dto;

import com.bipros.activity.domain.model.*;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateActivityRequest(
    String name,

    String description,

    UUID wbsNodeId,

    ActivityType activityType,

    DurationType durationType,

    PercentCompleteType percentCompleteType,

    @PositiveOrZero(message = "Original duration must be zero or positive")
    Double originalDuration,

    @PositiveOrZero(message = "Remaining duration must be zero or positive")
    Double remainingDuration,

    ActivityStatus status,

    @PositiveOrZero(message = "Percent complete must be zero or positive")
    Double percentComplete,

    @PositiveOrZero(message = "Physical percent complete must be zero or positive")
    Double physicalPercentComplete,

    LocalDate actualStartDate,

    LocalDate actualFinishDate,

    UUID calendarId,

    ConstraintType primaryConstraintType,

    LocalDate primaryConstraintDate,

    ConstraintType secondaryConstraintType,

    LocalDate secondaryConstraintDate,

    LocalDate suspendDate,

    LocalDate resumeDate,

    String notes,

    Long chainageFromM,

    Long chainageToM,

    /** Soft FK to {@code resource.work_activities.id}; pass {@code null} to leave unchanged. */
    UUID workActivityId,

    /** Soft FK to {@code cost.cost_accounts.id}; pass {@code null} to clear the assignment. */
    UUID costAccountId,

    /**
     * Soft FK to {@code resource.resources.id}. Pass to set/change the supervisor; pass
     * {@code null} (with a sentinel call) to leave unchanged. To clear we currently rely on
     * the controller passing both id + name as null and the service treating that as a clear.
     */
    UUID supervisorResourceId,

    /** Display-snapshot of the supervisor Resource name from the frontend picker. */
    String supervisorResourceName,

    /**
     * DBS-Phase-2: toggle the BOQ Preliminary flag. Pass {@code true} to mark the activity as a
     * Section 1 Preliminary item, {@code false} to demote it back to direct production. Pass
     * {@code null} to leave unchanged (consistent with the rest of this request's
     * "only-mutate-on-non-null" semantics).
     */
    Boolean preliminary
) {}
