package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.ProjectTeamMemberRequest;
import com.bipros.project.application.dto.ProjectTeamMemberResponse;
import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.ProjectTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * CRUD + resolver helpers for the project-scoped reporting line (see
 * {@link ProjectTeamMember}). The two {@code resolveXxx} methods back the DBS rollup —
 * they translate a supervisor's user id into their Engineer / PM so the aggregator can SUM
 * upwards.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProjectTeamService {

    private final ProjectTeamRepository teamRepository;
    private final ProjectRepository projectRepository;

    public ProjectTeamMemberResponse create(UUID projectId, ProjectTeamMemberRequest req) {
        ensureProjectExists(projectId);
        if (req.userId() == null) {
            throw new BusinessRuleException("PROJECT_TEAM_USER_REQUIRED", "userId is required");
        }
        ProjectRole role = parseRole(req.role());

        // Idempotent semantics on the unique key (project, user, role) — surface a clear
        // BusinessRuleException instead of letting Hibernate translate to a 500.
        teamRepository.findByProjectIdAndUserIdAndRole(projectId, req.userId(), role)
            .ifPresent(existing -> {
                throw new BusinessRuleException(
                    "PROJECT_TEAM_DUPLICATE",
                    "User %s already holds role %s on project %s".formatted(req.userId(), role, projectId));
            });

        ProjectTeamMember member = ProjectTeamMember.builder()
            .projectId(projectId)
            .userId(req.userId())
            .role(role)
            .reportsToUserId(req.reportsToUserId())
            .activeFrom(req.activeFrom())
            .activeTo(req.activeTo())
            .build();

        ProjectTeamMember saved = teamRepository.save(member);
        log.info("Created project_team member project={} user={} role={}", projectId, req.userId(), role);
        return toResponse(saved);
    }

    public ProjectTeamMemberResponse update(UUID projectId, UUID memberId, ProjectTeamMemberRequest req) {
        ensureProjectExists(projectId);
        ProjectTeamMember member = loadMember(projectId, memberId);

        if (req.userId() != null) {
            member.setUserId(req.userId());
        }
        if (req.role() != null) {
            member.setRole(parseRole(req.role()));
        }
        // reportsToUserId / active dates may legitimately be cleared to null — assign directly.
        member.setReportsToUserId(req.reportsToUserId());
        member.setActiveFrom(req.activeFrom());
        member.setActiveTo(req.activeTo());

        ProjectTeamMember saved = teamRepository.save(member);
        return toResponse(saved);
    }

    public void delete(UUID projectId, UUID memberId) {
        ensureProjectExists(projectId);
        ProjectTeamMember member = loadMember(projectId, memberId);
        teamRepository.delete(member);
        log.info("Deleted project_team member project={} memberId={}", projectId, memberId);
    }

    @Transactional(readOnly = true)
    public List<ProjectTeamMemberResponse> listForProject(UUID projectId) {
        ensureProjectExists(projectId);
        return teamRepository.findByProjectId(projectId).stream()
            .sorted(Comparator.comparing(ProjectTeamMember::getRole))
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectTeamMemberResponse> listByRole(UUID projectId, ProjectRole role) {
        ensureProjectExists(projectId);
        return teamRepository.findByProjectIdAndRole(projectId, role).stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * Find the SUPERVISOR membership for {@code supervisorUserId} on this project and return
     * the user id they report to (the Engineer). Empty when the supervisor isn't enrolled or
     * has no reporting line yet.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveEngineerFor(UUID projectId, UUID supervisorUserId) {
        return teamRepository
            .findByProjectIdAndUserIdAndRole(projectId, supervisorUserId, ProjectRole.SUPERVISOR)
            .map(ProjectTeamMember::getReportsToUserId);
    }

    /**
     * Return the user id of the first PM membership on this project. If the project has
     * multiple PMs (rare — usually outgoing handover) the first by repository order wins;
     * callers needing all PMs should use {@link #listByRole}.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolvePmFor(UUID projectId) {
        return teamRepository.findByProjectIdAndRole(projectId, ProjectRole.PM).stream()
            .findFirst()
            .map(ProjectTeamMember::getUserId);
    }

    /**
     * Walk the reporting chain from {@code startUserId} upward and return the first
     * CONSTRUCTION_MANAGER encountered. Returns empty if the chain does not include a
     * CM (e.g. legacy four-tier configuration without the CM seat).
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveCmFor(UUID projectId, UUID startUserId) {
        return walkUpChain(projectId, startUserId)
            .filter(m -> m.getRole() == ProjectRole.CONSTRUCTION_MANAGER)
            .findFirst()
            .map(ProjectTeamMember::getUserId);
    }

    /**
     * Walk the reporting chain from {@code startUserId} upward and return the first PM
     * encountered. Differs from {@link #resolvePmFor(UUID)} (which returns the project's
     * PM regardless of the caller's place in the hierarchy) — this overload walks the
     * chain edges, so an orphaned subtree without a PM at the top returns empty.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolvePmFor(UUID projectId, UUID startUserId) {
        return walkUpChain(projectId, startUserId)
            .filter(m -> m.getRole() == ProjectRole.PM)
            .findFirst()
            .map(ProjectTeamMember::getUserId);
    }

    /**
     * Stream the chain of {@link ProjectTeamMember} rows starting at {@code startUserId}
     * and following {@code reportsToUserId} edges. The starting row itself is included.
     * A visited-set guards against accidental cycles in the data.
     */
    private Stream<ProjectTeamMember> walkUpChain(UUID projectId, UUID startUserId) {
        if (startUserId == null) return Stream.empty();
        List<ProjectTeamMember> chain = new java.util.ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID cursor = startUserId;
        while (cursor != null && visited.add(cursor)) {
            List<ProjectTeamMember> rows = teamRepository.findAllByProjectIdAndUserId(projectId, cursor);
            if (rows.isEmpty()) break;
            ProjectTeamMember m = rows.get(0);
            chain.add(m);
            cursor = m.getReportsToUserId();
        }
        return chain.stream();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void ensureProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
    }

    private ProjectTeamMember loadMember(UUID projectId, UUID memberId) {
        ProjectTeamMember member = teamRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("ProjectTeamMember", memberId));
        if (!projectId.equals(member.getProjectId())) {
            throw new BusinessRuleException(
                "PROJECT_TEAM_PROJECT_MISMATCH",
                "Member %s does not belong to project %s".formatted(memberId, projectId));
        }
        return member;
    }

    private ProjectRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException("PROJECT_TEAM_ROLE_REQUIRED", "role is required");
        }
        try {
            return ProjectRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException(
                "PROJECT_TEAM_ROLE_INVALID",
                "Unknown project role: " + raw);
        }
    }

    private ProjectTeamMemberResponse toResponse(ProjectTeamMember m) {
        return new ProjectTeamMemberResponse(
            m.getId(),
            m.getProjectId(),
            m.getUserId(),
            m.getRole() != null ? m.getRole().name() : null,
            m.getReportsToUserId(),
            m.getActiveFrom(),
            m.getActiveTo(),
            m.getCreatedAt(),
            m.getUpdatedAt()
        );
    }
}
