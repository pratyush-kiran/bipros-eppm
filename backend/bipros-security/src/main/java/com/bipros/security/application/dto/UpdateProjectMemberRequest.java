package com.bipros.security.application.dto;

import com.bipros.security.domain.model.ProjectMemberRole;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PUT /v1/projects/{projectId}/members/{memberId}} — atomic
 * change of a member's project-scoped role. Replaces the frontend's previous
 * DELETE+POST pair so the swap is observable as a single transaction.
 */
public record UpdateProjectMemberRequest(
        @NotNull ProjectMemberRole projectRole
) {
}
