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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies {@link ProjectTeamService#resolveApprover(UUID, UUID)}: finds the FIRST person
 * strictly ABOVE the submitter in the project reporting chain who holds DPR.APPROVE.
 * The submitter is never their own approver (separation of duties).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectTeamService — DPR approver resolution")
class ProjectTeamServiceApproverResolutionTest {

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

    // ── helper ──────────────────────────────────────────────────────────────────

    private ProjectTeamMember member(UUID userId, ProjectRole role, UUID reportsTo) {
        return ProjectTeamMember.builder()
            .projectId(projectId)
            .userId(userId)
            .role(role)
            .reportsToUserId(reportsTo)
            .build();
    }

    private void stubChainRow(UUID userId, ProjectRole role, UUID reportsTo) {
        lenient().when(teamRepository.findAllByProjectIdAndUserId(projectId, userId))
            .thenReturn(List.of(member(userId, role, reportsTo)));
    }

    private void stubDprApprove(UUID userId, boolean hasIt) {
        lenient().when(userPermissionPort.hasPermission(userId, ProjectTeamService.DPR_APPROVE))
            .thenReturn(hasIt);
    }

    // ── test 1: skip-self ────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. submitter holds DPR.APPROVE but is excluded (sep-of-duties); manager above also has it → returns manager")
    void skipSelf_returnManagerAbove() {
        UUID supId = UUID.randomUUID();
        UUID mgrId = UUID.randomUUID();

        stubChainRow(supId, ProjectRole.SUPERVISOR, mgrId);
        stubChainRow(mgrId, ProjectRole.ENGINEER,   null);

        stubDprApprove(supId, true);   // submitter has DPR.APPROVE — must be skipped
        stubDprApprove(mgrId, true);   // manager also has it

        Optional<UUID> result = service.resolveApprover(projectId, supId);

        assertThat(result).contains(mgrId);
    }

    // ── test 2: escalate-up ──────────────────────────────────────────────────────

    @Test
    @DisplayName("2. immediate manager lacks DPR.APPROVE; CM above has it → returns CM")
    void escalatesUpToCm() {
        UUID supId = UUID.randomUUID();
        UUID engId = UUID.randomUUID();
        UUID cmId  = UUID.randomUUID();

        stubChainRow(supId, ProjectRole.SUPERVISOR,           engId);
        stubChainRow(engId, ProjectRole.ENGINEER,             cmId);
        stubChainRow(cmId,  ProjectRole.CONSTRUCTION_MANAGER, null);

        stubDprApprove(engId, false);
        stubDprApprove(cmId,  true);

        Optional<UUID> result = service.resolveApprover(projectId, supId);

        assertThat(result).contains(cmId);
    }

    // ── test 3: first-capable (nearest) ──────────────────────────────────────────

    @Test
    @DisplayName("3. engineer lacks, CM has, PM has → returns CM (nearest capable, not topmost)")
    void returnsNearestCapable() {
        UUID supId = UUID.randomUUID();
        UUID engId = UUID.randomUUID();
        UUID cmId  = UUID.randomUUID();
        UUID pmId  = UUID.randomUUID();

        stubChainRow(supId, ProjectRole.SUPERVISOR,           engId);
        stubChainRow(engId, ProjectRole.ENGINEER,             cmId);
        stubChainRow(cmId,  ProjectRole.CONSTRUCTION_MANAGER, pmId);
        stubChainRow(pmId,  ProjectRole.PM,                   null);

        stubDprApprove(engId, false);
        stubDprApprove(cmId,  true);
        stubDprApprove(pmId,  true);

        Optional<UUID> result = service.resolveApprover(projectId, supId);

        assertThat(result).contains(cmId);
    }

    // ── test 4: unassigned ───────────────────────────────────────────────────────

    @Test
    @DisplayName("4. nobody above holds DPR.APPROVE → Optional.empty()")
    void noCapableApprover_returnsEmpty() {
        UUID supId = UUID.randomUUID();
        UUID engId = UUID.randomUUID();
        UUID pmId  = UUID.randomUUID();

        stubChainRow(supId, ProjectRole.SUPERVISOR, engId);
        stubChainRow(engId, ProjectRole.ENGINEER,   pmId);
        stubChainRow(pmId,  ProjectRole.PM,         null);

        stubDprApprove(engId, false);
        stubDprApprove(pmId,  false);

        Optional<UUID> result = service.resolveApprover(projectId, supId);

        assertThat(result).isEmpty();
    }

    // ── test 5: no reporting line ────────────────────────────────────────────────

    @Test
    @DisplayName("5. submitter not in team (no chain rows) → Optional.empty()")
    void submitterNotInTeam_returnsEmpty() {
        UUID unknownId = UUID.randomUUID();
        lenient().when(teamRepository.findAllByProjectIdAndUserId(projectId, unknownId))
            .thenReturn(List.of());

        Optional<UUID> result = service.resolveApprover(projectId, unknownId);

        assertThat(result).isEmpty();
    }

    // ── test 6: null submitter ───────────────────────────────────────────────────

    @Test
    @DisplayName("6. null submitterUserId → Optional.empty() with no repo interaction")
    void nullSubmitter_returnsEmpty() {
        Optional<UUID> result = service.resolveApprover(projectId, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(teamRepository);
    }
}
