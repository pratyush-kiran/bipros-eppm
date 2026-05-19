package com.bipros.project.application.service;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ProjectTeamService#resolveCmFor(UUID, UUID)} walks the five-tier
 * reporting chain (PM ← CM ← SiteManager ← Engineer ← Supervisor) and returns the
 * first CONSTRUCTION_MANAGER it encounters, while the refactored
 * {@link ProjectTeamService#resolvePmFor(UUID, UUID)} keeps walking past the CM to
 * reach the PM at the top. Plan Phase 1, Task 1.4.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectTeamService — CM resolution along reporting chain")
class ProjectTeamServiceCmResolutionTest {

    @Mock private ProjectTeamRepository teamRepository;
    @Mock private ProjectRepository projectRepository;

    private ProjectTeamService service;

    // The fake reporting line, keyed by userId for fast lookup in `mockChain`.
    private final Map<UUID, ProjectTeamMember> chainByUser = new HashMap<>();

    private UUID projectId;
    private UUID pmId, cmId, smId, engId, supId;

    @BeforeEach
    void setUp() {
        service = new ProjectTeamService(teamRepository, projectRepository);

        projectId = UUID.randomUUID();
        pmId  = UUID.randomUUID();
        cmId  = UUID.randomUUID();
        smId  = UUID.randomUUID();
        engId = UUID.randomUUID();
        supId = UUID.randomUUID();

        addMember(pmId,  ProjectRole.PM,                   null);
        addMember(cmId,  ProjectRole.CONSTRUCTION_MANAGER, pmId);
        addMember(smId,  ProjectRole.SITE_MANAGER,         cmId);
        addMember(engId, ProjectRole.ENGINEER,             smId);
        addMember(supId, ProjectRole.SUPERVISOR,           engId);

        // Stub the repository lookup: any (projectId, userId) returns the matching
        // member if it's in `chainByUser`, otherwise an empty list.
        when(teamRepository.findAllByProjectIdAndUserId(any(UUID.class), any(UUID.class)))
            .thenAnswer(invocation -> {
                UUID userId = invocation.getArgument(1);
                ProjectTeamMember m = chainByUser.get(userId);
                return m == null ? List.of() : List.of(m);
            });
    }

    @Test
    @DisplayName("resolveCmFor walks Supervisor → Engineer → SiteManager → CM and returns the CM")
    void resolvesCmThroughFullChain() {
        Optional<UUID> resolved = service.resolveCmFor(projectId, supId);
        assertThat(resolved).contains(cmId);
    }

    @Test
    @DisplayName("resolveCmFor returns empty when no CM is in the chain")
    void returnsEmptyWhenCmAbsent() {
        // Remove the CM and re-point SiteManager directly at the PM —
        // matches the legacy 4-tier configuration.
        chainByUser.remove(cmId);
        chainByUser.get(smId).setReportsToUserId(pmId);

        Optional<UUID> resolved = service.resolveCmFor(projectId, supId);
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("resolvePmFor(supervisor) walks past the CM and returns the PM")
    void resolvesPmThroughCm() {
        Optional<UUID> resolved = service.resolvePmFor(projectId, supId);
        assertThat(resolved).contains(pmId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void addMember(UUID userId, ProjectRole role, UUID reportsTo) {
        ProjectTeamMember m = ProjectTeamMember.builder()
            .projectId(projectId)
            .userId(userId)
            .role(role)
            .reportsToUserId(reportsTo)
            .build();
        chainByUser.put(userId, m);
    }
}
