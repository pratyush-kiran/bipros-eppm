package com.bipros.security.application.service;

import com.bipros.project.domain.repository.ProjectTeamRepository;
import com.bipros.security.domain.model.ProjectMemberRole;
import com.bipros.security.domain.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Exposes {@link ProjectAccessService} to {@code @PreAuthorize} SpEL.
 *
 * <p>Usage:
 * <pre>
 *   &#64;PreAuthorize("@projectAccess.canEdit(#projectId)")
 *   public ResponseEntity&lt;...&gt; updateActivity(@PathVariable UUID projectId, ...) { ... }
 *
 *   &#64;PreAuthorize("@projectAccess.canRead(#projectId)")
 *   public ResponseEntity&lt;...&gt; listActivities(@PathVariable UUID projectId, ...) { ... }
 *
 *   &#64;PreAuthorize("@projectAccess.hasProjectRole(#projectId, 'PROJECT_MANAGER')")
 *   public ResponseEntity&lt;...&gt; assignTeamMember(@PathVariable UUID projectId, ...) { ... }
 *
 *   &#64;PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
 *   public ResponseEntity&lt;...&gt; approveDpr(@PathVariable UUID projectId, ...) { ... }
 * </pre>
 *
 * <p>Service-layer enforcement (via {@link ProjectAccessService#requireEdit}) remains the
 * source of truth — these annotations are guardrails that fail-fast at the controller boundary.
 */
@Slf4j
@Component("projectAccess")
@RequiredArgsConstructor
public class ProjectAccessEvaluator {

    private final ProjectAccessService projectAccessService;
    private final CurrentUserService currentUserService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectTeamRepository projectTeamRepository;

    public boolean canRead(UUID projectId) {
        return projectAccessService.canRead(currentUserService.getCurrentUserId(), projectId);
    }

    public boolean canEdit(UUID projectId) {
        return projectAccessService.canEdit(currentUserService.getCurrentUserId(), projectId);
    }

    public boolean canDelete(UUID projectId) {
        return projectAccessService.canDelete(currentUserService.getCurrentUserId(), projectId);
    }

    public boolean hasProjectRole(UUID projectId, String role) {
        ProjectMemberRole r;
        try {
            r = ProjectMemberRole.valueOf(role);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown ProjectMemberRole in @PreAuthorize: {}", role);
            return false;
        }
        return projectAccessService.hasProjectRole(currentUserService.getCurrentUserId(), projectId, r);
    }

    /**
     * True iff the current user has the named permission globally AND is a member of the project
     * (or is ADMIN). Used by {@code @PreAuthorize} SpEL: e.g.
     * {@code @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")}.
     *
     * <p>Introduced in <b>Phase 2 of the RBAC overhaul</b> as the project-scoped counterpart to
     * {@link CurrentUserService#hasPermission(String)} (which is global / unscoped). Phase 3 will
     * migrate controllers from role-name checks to this fine-grained gate.
     *
     * <p>Semantics:
     * <ul>
     *   <li>System context (no Authentication, e.g. seeder threads) → always true.</li>
     *   <li>ADMIN authority → always true (admin escape hatch).</li>
     *   <li>{@code permissionCode} or {@code projectId} null → false.</li>
     *   <li>Permission missing globally → false.</li>
     *   <li>Permission granted but not a {@code ProjectMember} of {@code projectId} → false.</li>
     *   <li>Otherwise → true.</li>
     * </ul>
     *
     * <p>Membership is checked against the same {@code project_members} table that
     * {@link ProjectAccessService#canRead(UUID, UUID)} and {@link ProjectAccessService#canEdit(UUID, UUID)}
     * consult — OBS-tree-only access does NOT satisfy this check.
     */
    public boolean hasProjectPermission(UUID projectId, String permissionCode) {
        if (currentUserService.isSystemContext()) {
            return true;
        }
        if (currentUserService.isAdmin()) {
            return true;
        }
        if (permissionCode == null || projectId == null) {
            return false;
        }
        if (!currentUserService.hasPermission(permissionCode)) {
            return false;
        }
        return isProjectMember(projectId);
    }

    /**
     * True iff the current user has at least one membership row for {@code projectId} — either
     * a legacy {@code project_members} row (security-managed) OR a {@code project.project_team}
     * row (DBS reporting line; how pilot PM/CM/Engineer/Supervisor and the DPR-filing roles are
     * wired). Either source qualifies the user as a "project member" for permission gating.
     *
     * <p>Mirrors the membership union in {@link ProjectAccessService#canRead(UUID, UUID)} so
     * that {@code @PreAuthorize("@projectAccess.hasProjectPermission(...)")} agrees with the
     * service-layer {@code requireRead/requireEdit} on who counts as a member.
     */
    private boolean isProjectMember(UUID projectId) {
        UUID userId = currentUserService.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        if (!projectMemberRepository.findByUserIdAndProjectId(userId, projectId).isEmpty()) {
            return true;
        }
        return !projectTeamRepository.findAllByProjectIdAndUserId(projectId, userId).isEmpty();
    }
}
