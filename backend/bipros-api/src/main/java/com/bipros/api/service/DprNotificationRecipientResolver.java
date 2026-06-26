package com.bipros.api.service;

import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Resolves the recipients for DPR approval notifications, with PM+admins fallbacks. */
@Service
@RequiredArgsConstructor
public class DprNotificationRecipientResolver {

    private final ProjectTeamService projectTeamService;
    private final UserRepository userRepository;

    /** Who to tell that a DPR is awaiting approval: the assigned approver, or PM+admins if unassigned. */
    public Set<UUID> arrivalRecipients(DailyProgressReport dpr) {
        if (dpr.getAssignedApproverUserId() != null) {
            return Set.of(dpr.getAssignedApproverUserId());
        }
        return pmAndAdmins(dpr.getProjectId());
    }

    /** Who to escalate an overdue DPR to: the approver's immediate manager, else PM+admins. */
    public Set<UUID> escalationManagers(DailyProgressReport dpr) {
        UUID approver = dpr.getAssignedApproverUserId();
        if (approver != null) {
            Optional<UUID> mgr = projectTeamService.getImmediateReporter(dpr.getProjectId(), approver);
            if (mgr.isPresent()) return Set.of(mgr.get());
        }
        return pmAndAdmins(dpr.getProjectId()); // unassigned OR approver has no manager
    }

    private Set<UUID> pmAndAdmins(UUID projectId) {
        Set<UUID> out = new HashSet<>();
        projectTeamService.resolvePmFor(projectId).ifPresent(out::add);
        for (User u : userRepository.findByRoleNamesAndEnabled(List.of("ADMIN"))) {
            out.add(u.getId());
        }
        return out;
    }
}
