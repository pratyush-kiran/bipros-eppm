package com.bipros.security.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.api.ProjectMemberController.MemberDto;
import com.bipros.security.domain.model.ProjectMember;
import com.bipros.security.domain.model.ProjectMemberRole;
import com.bipros.security.domain.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Atomic project-membership operations. Today exposes only the role-update
 * path so the frontend can replace its DELETE+POST swap with a single PUT;
 * list / assign / revoke remain on {@code ProjectMemberController} backed
 * directly by the repository.
 *
 * <p>{@code updatedBy} is stamped automatically by the JPA auditor on
 * {@code BaseEntity} — no manual call required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserService currentUserService;

    /**
     * Updates the project-scoped role of an existing member.
     *
     * <p>Validates that the member belongs to the given {@code projectId};
     * a path/projectId mismatch yields a {@link ResourceNotFoundException}
     * rather than leaking that the id exists under a different project.
     *
     * @throws ResourceNotFoundException if no ProjectMember with the given id
     *                                   exists under the given project
     */
    @Transactional
    public MemberDto updateMemberRole(UUID projectId, UUID memberId, ProjectMemberRole newRole) {
        ProjectMember member = projectMemberRepository.findById(memberId)
                .filter(m -> projectId.equals(m.getProjectId()))
                .orElseThrow(() -> new ResourceNotFoundException("ProjectMember", memberId));
        ProjectMemberRole oldRole = member.getProjectRole();
        member.setProjectRole(newRole);
        ProjectMember saved = projectMemberRepository.save(member);
        log.info("ProjectMember role updated: id={} projectId={} userId={} oldRole={} newRole={} by={}",
                memberId, projectId, member.getUserId(), oldRole, newRole,
                currentUserService.getCurrentUserId());
        return MemberDto.from(saved);
    }
}
