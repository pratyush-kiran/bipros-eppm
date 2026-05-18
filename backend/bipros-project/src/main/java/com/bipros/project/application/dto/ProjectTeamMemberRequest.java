package com.bipros.project.application.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Create / update payload for a {@code project_team} membership row. {@code role} is the
 * string form of {@link com.bipros.project.domain.model.ProjectRole} and is validated by the
 * service.
 */
public record ProjectTeamMemberRequest(
        UUID userId,
        String role,
        UUID reportsToUserId,
        LocalDate activeFrom,
        LocalDate activeTo
) {
}
