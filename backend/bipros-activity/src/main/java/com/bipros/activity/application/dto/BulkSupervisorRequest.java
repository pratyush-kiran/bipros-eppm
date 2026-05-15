package com.bipros.activity.application.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * @deprecated Phase 4.5: payload for the legacy bulk-supervisor endpoint that wrote to the
 * dropped {@code responsibleResourceId} / {@code responsibleResourceName} columns. The
 * accepting service method ({@code ActivityService.bulkSetSupervisor}) now short-circuits to
 * a no-op so older clients stop receiving 500s — but no assignment is performed. Use the
 * per-activity {@code PUT /v1/activities/{id}/supervisor} endpoint (writes
 * {@code supervisor_user_id}) instead.
 */
@Deprecated(forRemoval = true)
public record BulkSupervisorRequest(
    @NotNull(message = "supervisorResourceId is required")
    UUID supervisorResourceId,

    /** Display-snapshot name from the picker option label. Goes stale if the Resource
     * is renamed later — accepted limitation, mirrors the per-activity supervisor field. */
    String supervisorResourceName,

    @NotEmpty(message = "activityIds must contain at least one activity")
    List<UUID> activityIds
) {}
