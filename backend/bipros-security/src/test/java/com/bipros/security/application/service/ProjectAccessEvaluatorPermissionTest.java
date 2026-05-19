package com.bipros.security.application.service;

import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import com.bipros.project.domain.repository.ProjectTeamRepository;
import com.bipros.security.domain.model.ProjectMember;
import com.bipros.security.domain.repository.ProjectMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectAccessEvaluator#hasProjectPermission(UUID, String)} —
 * Phase 2.C of the RBAC overhaul.
 *
 * <p>Pins the AND semantics: a user needs BOTH the global permission AND project membership
 * (or be ADMIN / system context). Counterpart to {@link CurrentUserServiceHasPermissionTest}
 * (which covers the unscoped global predicate).
 */
@ExtendWith(MockitoExtension.class)
class ProjectAccessEvaluatorPermissionTest {

    private static final String PERMISSION = "DPR.APPROVE";

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectTeamRepository projectTeamRepository;

    @InjectMocks
    private ProjectAccessEvaluator evaluator;

    // ── Cases ────────────────────────────────────────────────────────────────

    @Test
    void systemContext_returnsTrue() {
        when(currentUserService.isSystemContext()).thenReturn(true);

        boolean allowed = evaluator.hasProjectPermission(UUID.randomUUID(), PERMISSION);

        assertThat(allowed).isTrue();
        // No permission lookup, no membership lookup — system bypass short-circuits everything.
        verify(currentUserService, never()).hasPermission(anyString());
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }

    @Test
    void adminUser_returnsTrueWithoutMembershipCheck() {
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(true);

        boolean allowed = evaluator.hasProjectPermission(UUID.randomUUID(), PERMISSION);

        assertThat(allowed).isTrue();
        verify(currentUserService, never()).hasPermission(anyString());
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }

    @Test
    void nullPermissionCode_returnsFalse() {
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);

        boolean allowed = evaluator.hasProjectPermission(UUID.randomUUID(), null);

        assertThat(allowed).isFalse();
        verify(currentUserService, never()).hasPermission(anyString());
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }

    @Test
    void nullProjectId_returnsFalse() {
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);

        boolean allowed = evaluator.hasProjectPermission(null, PERMISSION);

        assertThat(allowed).isFalse();
        verify(currentUserService, never()).hasPermission(anyString());
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }

    @Test
    void hasPermissionButNotMember_returnsFalse() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);
        when(currentUserService.hasPermission(PERMISSION)).thenReturn(true);
        when(currentUserService.getCurrentUserId()).thenReturn(userId);
        when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
                .thenReturn(List.of());

        boolean allowed = evaluator.hasProjectPermission(projectId, PERMISSION);

        assertThat(allowed).isFalse();
    }

    @Test
    void memberButNoPermission_returnsFalse() {
        UUID projectId = UUID.randomUUID();
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);
        when(currentUserService.hasPermission(PERMISSION)).thenReturn(false);

        boolean allowed = evaluator.hasProjectPermission(projectId, PERMISSION);

        assertThat(allowed).isFalse();
        // Membership query is skipped once the permission check fails.
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }

    @Test
    void hasPermissionAndIsMember_returnsTrue() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);
        when(currentUserService.hasPermission(PERMISSION)).thenReturn(true);
        when(currentUserService.getCurrentUserId()).thenReturn(userId);
        when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
                .thenReturn(List.of(new ProjectMember()));

        boolean allowed = evaluator.hasProjectPermission(projectId, PERMISSION);

        assertThat(allowed).isTrue();
    }

    @Test
    void hasPermissionAndIsProjectTeamMember_returnsTrue() {
        // Regression for DA-RBAC-01/02/03: a user wired in project.project_team (DBS reporting
        // line) but NOT in the legacy project_members table should still be granted project
        // permissions. This is how pilot.pm1 / pilot.cm1 / pilot.eng1 / pilot.sup1 are seeded.
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ProjectTeamMember teamRow = ProjectTeamMember.builder()
                .userId(userId)
                .projectId(projectId)
                .role(ProjectRole.SUPERVISOR)
                .build();
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);
        when(currentUserService.hasPermission(PERMISSION)).thenReturn(true);
        when(currentUserService.getCurrentUserId()).thenReturn(userId);
        when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
                .thenReturn(List.of());
        when(projectTeamRepository.findAllByProjectIdAndUserId(projectId, userId))
                .thenReturn(List.of(teamRow));

        boolean allowed = evaluator.hasProjectPermission(projectId, PERMISSION);

        assertThat(allowed).isTrue();
    }

    @Test
    void hasPermissionButCurrentUserIdNull_returnsFalse() {
        // Edge case: permission granted (e.g. via anonymous role wiring) but principal can't be
        // resolved to a user row — membership cannot be established, so deny.
        UUID projectId = UUID.randomUUID();
        when(currentUserService.isSystemContext()).thenReturn(false);
        when(currentUserService.isAdmin()).thenReturn(false);
        when(currentUserService.hasPermission(PERMISSION)).thenReturn(true);
        when(currentUserService.getCurrentUserId()).thenReturn(null);

        boolean allowed = evaluator.hasProjectPermission(projectId, PERMISSION);

        assertThat(allowed).isFalse();
        verify(projectMemberRepository, never()).findByUserIdAndProjectId(any(), any());
    }
}
