package com.bipros.project.application.service;

import com.bipros.common.security.UserPermissionPort;
import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.ProjectTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ProjectTeamService#getImmediateReporter(UUID, UUID)}: returns the
 * reportsToUserId from the first membership row, or empty when the user is absent or null.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectTeamService — getImmediateReporter")
class ProjectTeamServiceImmediateReporterTest {

    @Mock private ProjectTeamRepository teamRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private UserPermissionPort userPermissionPort;

    private ProjectTeamService service;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        service = new ProjectTeamService(teamRepository, projectRepository, userPermissionPort);
        projectId = UUID.randomUUID();
    }

    @Test
    @DisplayName("1. membership row exists with reportsToUserId → returns that id")
    void returnsReportsToUserId_whenMembershipExists() {
        UUID userId = UUID.randomUUID();
        UUID reportsTo = UUID.randomUUID();

        ProjectTeamMember member = ProjectTeamMember.builder()
            .projectId(projectId)
            .userId(userId)
            .role(ProjectRole.SUPERVISOR)
            .reportsToUserId(reportsTo)
            .build();

        when(teamRepository.findAllByProjectIdAndUserId(projectId, userId))
            .thenReturn(List.of(member));

        Optional<UUID> result = service.getImmediateReporter(projectId, userId);

        assertThat(result).contains(reportsTo);
    }

    @Test
    @DisplayName("2. no membership rows → Optional.empty()")
    void returnsEmpty_whenNoMembership() {
        UUID userId = UUID.randomUUID();

        when(teamRepository.findAllByProjectIdAndUserId(projectId, userId))
            .thenReturn(List.of());

        Optional<UUID> result = service.getImmediateReporter(projectId, userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("3. null userId → Optional.empty() with no repo interaction")
    void returnsEmpty_whenUserIdIsNull() {
        Optional<UUID> result = service.getImmediateReporter(projectId, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(teamRepository);
    }
}
