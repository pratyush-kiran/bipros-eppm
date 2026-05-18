package com.bipros.common.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by DailyResourceDeploymentService on CREATE/UPDATE/DELETE. Consumed by the
 * DBS rollup listener to recompute the day's totals for the affected supervisor/project.
 */
public record ResourceDeploymentSavedEvent(
    UUID projectId,
    UUID deploymentId,
    LocalDate logDate,
    String resourceType,
    UUID resourceId,
    UUID resourceRoleId,
    EventType eventType
) {
    public enum EventType { CREATED, UPDATED, DELETED }
}
