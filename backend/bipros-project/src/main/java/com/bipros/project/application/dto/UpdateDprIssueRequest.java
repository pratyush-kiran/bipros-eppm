package com.bipros.project.application.dto;

import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial-update request for the dedicated issue PATCH endpoint. All fields are nullable; only
 * non-null fields are applied. The service auto-manages {@code resolvedAt} on status transitions
 * (set to {@code now()} on entering RESOLVED/CLOSED, cleared on leaving them).
 *
 * <p>Immutable fields deliberately absent: {@code dprId}, {@code projectId}, {@code reportDate},
 * {@code openedAt}, {@code chainage}. Activity is patchable for standalone issues (dprId=null);
 * for DPR-bound issues the activity snapshot is preserved by service convention.
 */
public record UpdateDprIssueRequest(
    @Size(max = 150) String title,
    @Size(max = 2000) String description,
    IssueCategory category,
    IssueSeverity severity,
    IssueStatus status,
    UUID supervisorResourceId,
    String supervisorName,
    UUID assignedToResourceId,
    String assignedToName,
    @Size(max = 1000) String resolutionNotes,
    UUID supervisorUserId,
    UUID assignedToUserId,
    UUID activityId,
    @Size(max = 150) String activityName
) {
}
