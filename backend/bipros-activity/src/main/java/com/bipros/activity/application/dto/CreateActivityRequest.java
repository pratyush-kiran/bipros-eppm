package com.bipros.activity.application.dto;

import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.PercentCompleteType;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.UUID;

public record CreateActivityRequest(
    @NotBlank(message = "Code is required")
    String code,

    @NotBlank(message = "Name is required")
    String name,

    String description,

    @NotNull(message = "Project ID is required")
    UUID projectId,

    @NotNull(message = "WBS Node ID is required")
    UUID wbsNodeId,

    ActivityType activityType,

    DurationType durationType,

    PercentCompleteType percentCompleteType,

    @PositiveOrZero(message = "Original duration must be zero or positive")
    Double originalDuration,

    LocalDate plannedStartDate,

    LocalDate plannedFinishDate,

    UUID calendarId,

    Long chainageFromM,

    Long chainageToM,

    /**
     * Soft FK to {@code resource.work_activities.id}. Optional — when present, links this
     * project activity to its master/library entry so productivity norms can be resolved
     * per (activity, deployed resource).
     */
    UUID workActivityId,

    /**
     * Soft FK to {@code cost.cost_accounts.id}. Optional — when present, assigns the activity to
     * a cost account directly, overriding any cost account inherited from the WBS node.
     */
    UUID costAccountId,

    // Deprecated (Phase 4.5): ignored by ActivityService.createActivity. The legacy
    // Activity.responsibleResourceId cache was dropped by Liquibase 094; supervisor
    // identity is now assigned via PUT /v1/activities/{id}/supervisor
    // (Activity.supervisorUserId). Retained on the request to keep older frontends compiling.
    UUID supervisorResourceId,

    // Deprecated (Phase 4.5): ignored by ActivityService.createActivity (the display
    // cache it fed is gone — see supervisorResourceId).
    String supervisorResourceName
) {}
