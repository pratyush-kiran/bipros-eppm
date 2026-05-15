package com.bipros.security.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.security.application.dto.UpdateProjectMemberRequest;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.security.application.service.ProjectMemberService;
import com.bipros.security.domain.model.ProjectMember;
import com.bipros.security.domain.model.ProjectMemberRole;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.ProjectMemberRepository;
import com.bipros.security.domain.repository.UserRepository;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Manage per-project role assignments. Callers need {@code PROJECT_MEMBER.MANAGE} on the project
 * to assign / revoke members and {@code PROJECT_MEMBER.READ} to list them. ADMIN is short-circuited
 * by {@code CustomPermissionEvaluator}, so no explicit ADMIN escape hatch is required.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /v1/projects/{projectId}/members}        — list members</li>
 *   <li>{@code POST   /v1/projects/{projectId}/members}        — assign a role</li>
 *   <li>{@code PUT    /v1/projects/{projectId}/members/{id}}   — change an existing member's role</li>
 *   <li>{@code DELETE /v1/projects/{projectId}/members/{id}}   — revoke an assignment</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMemberService projectMemberService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    public record AssignRequest(UUID userId, ProjectMemberRole role) {}

    /**
     * Project-member row returned to the UI. Embeds the assigned user's display fields so the
     * page renders names without a follow-up {@code /v1/users} call — that endpoint requires
     * {@code ADMIN_USER.READ} which project-scoped users (PM, etc.) typically lack.
     */
    public record MemberDto(
            UUID id,
            UUID userId,
            UUID projectId,
            ProjectMemberRole role,
            UUID grantedBy,
            String username,
            String firstName,
            String lastName,
            String email,
            String grantedByUsername,
            String grantedByName
    ) {
        public static MemberDto from(ProjectMember m) {
            return new MemberDto(m.getId(), m.getUserId(), m.getProjectId(), m.getProjectRole(),
                    m.getGrantedBy(), null, null, null, null, null, null);
        }

        public static MemberDto from(ProjectMember m, User user, User grantedByUser) {
            String grantedByDisplay = grantedByUser == null ? null : displayName(grantedByUser);
            return new MemberDto(
                    m.getId(),
                    m.getUserId(),
                    m.getProjectId(),
                    m.getProjectRole(),
                    m.getGrantedBy(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getFirstName(),
                    user == null ? null : user.getLastName(),
                    user == null ? null : user.getEmail(),
                    grantedByUser == null ? null : grantedByUser.getUsername(),
                    grantedByDisplay
            );
        }

        private static String displayName(User u) {
            String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
            String last = u.getLastName() == null ? "" : u.getLastName().trim();
            String full = (first + " " + last).trim();
            return full.isEmpty() ? u.getUsername() : full;
        }
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT_MEMBER.READ')")
    public ResponseEntity<ApiResponse<List<MemberDto>>> list(@PathVariable UUID projectId) {
        List<ProjectMember> rows = projectMemberRepository.findByProjectId(projectId);

        // Batch-load every distinct user referenced by these rows (assigned + granter) so the
        // page doesn't fan out to N+1 queries or need a separate ADMIN_USER.READ-protected call.
        java.util.Set<UUID> userIds = new java.util.HashSet<>();
        for (ProjectMember m : rows) {
            if (m.getUserId() != null) userIds.add(m.getUserId());
            if (m.getGrantedBy() != null) userIds.add(m.getGrantedBy());
        }
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<MemberDto> members = rows.stream()
                .map(m -> MemberDto.from(m, usersById.get(m.getUserId()), usersById.get(m.getGrantedBy())))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(members));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT_MEMBER.MANAGE')")
    @Transactional
    public ResponseEntity<ApiResponse<MemberDto>> assign(
            @PathVariable UUID projectId,
            @RequestBody AssignRequest request) {
        if (request.userId() == null || request.role() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "userId and role are required"));
        }
        if (projectMemberRepository.existsByUserIdAndProjectIdAndProjectRole(
                request.userId(), projectId, request.role())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("DUPLICATE", "User already has this role on the project"));
        }
        ProjectMember member = new ProjectMember(
                request.userId(), projectId, request.role(), currentUserService.getCurrentUserId());
        ProjectMember saved = projectMemberRepository.save(member);
        log.info("ProjectMember assigned: userId={} projectId={} role={} by={}",
                request.userId(), projectId, request.role(), currentUserService.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(MemberDto.from(saved)));
    }

    @PutMapping("/{memberId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT_MEMBER.MANAGE')")
    public ResponseEntity<ApiResponse<MemberDto>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateProjectMemberRequest request) {
        MemberDto updated = projectMemberService.updateMemberRole(
                projectId, memberId, request.projectRole());
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT_MEMBER.MANAGE')")
    @Transactional
    public ResponseEntity<Void> revoke(@PathVariable UUID projectId, @PathVariable UUID memberId) {
        projectMemberRepository.findById(memberId).ifPresent(m -> {
            if (!projectId.equals(m.getProjectId())) {
                // path/projectId mismatch — treat as not-found rather than leak existence
                return;
            }
            projectMemberRepository.delete(m);
            log.info("ProjectMember revoked: id={} projectId={} by={}",
                    memberId, projectId, currentUserService.getCurrentUserId());
        });
        return ResponseEntity.noContent().build();
    }
}
