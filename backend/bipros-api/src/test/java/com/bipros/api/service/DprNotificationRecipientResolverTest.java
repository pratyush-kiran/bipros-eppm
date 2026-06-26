package com.bipros.api.service;

import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Verifies {@link DprNotificationRecipientResolver}: arrival + escalation recipients with
 * PM+admins fallback logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DprNotificationRecipientResolver")
class DprNotificationRecipientResolverTest {

    @Mock private ProjectTeamService projectTeamService;
    @Mock private UserRepository userRepository;

    private DprNotificationRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DprNotificationRecipientResolver(projectTeamService, userRepository);
    }

    // ── helper ──────────────────────────────────────────────────────────────────

    private DailyProgressReport dpr(UUID projectId, UUID assignedApproverUserId) {
        return DailyProgressReport.builder()
            .projectId(projectId)
            .reportDate(java.time.LocalDate.now())
            .supervisorName("Test")
            .activityName("Test Activity")
            .unit("m")
            .qtyExecuted(java.math.BigDecimal.ONE)
            .assignedApproverUserId(assignedApproverUserId)
            .build();
    }

    // ── test 1: assigned approver ────────────────────────────────────────────────

    @Test
    @DisplayName("1a. assigned approver → arrivalRecipients = just the approver")
    void arrivalRecipients_assignedApprover() {
        UUID projectId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        DailyProgressReport dpr = dpr(projectId, approverId);

        Set<UUID> result = resolver.arrivalRecipients(dpr);

        assertThat(result).containsExactly(approverId);
    }

    @Test
    @DisplayName("1b. assigned approver with manager → escalationManagers = the manager")
    void escalationManagers_assignedApproverWithManager() {
        UUID projectId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        DailyProgressReport dpr = dpr(projectId, approverId);

        when(projectTeamService.getImmediateReporter(projectId, approverId))
            .thenReturn(Optional.of(managerId));

        Set<UUID> result = resolver.escalationManagers(dpr);

        assertThat(result).containsExactly(managerId);
    }

    // ── test 2: unassigned → PM + admins ─────────────────────────────────────────

    @Test
    @DisplayName("2a. unassigned → arrivalRecipients = PM + admins")
    void arrivalRecipients_unassigned_pmAndAdmins() {
        UUID projectId = UUID.randomUUID();
        UUID pmId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        // Pre-create the user mock before any when() stubs to avoid UnfinishedStubbing
        User adminUser = mock(User.class);
        when(adminUser.getId()).thenReturn(adminId);
        DailyProgressReport dpr = dpr(projectId, null);

        when(projectTeamService.resolvePmFor(projectId)).thenReturn(Optional.of(pmId));
        when(userRepository.findByRoleNamesAndEnabled(List.of("ADMIN")))
            .thenReturn(List.of(adminUser));

        Set<UUID> result = resolver.arrivalRecipients(dpr);

        assertThat(result).containsExactlyInAnyOrder(pmId, adminId);
    }

    @Test
    @DisplayName("2b. unassigned → escalationManagers = PM + admins")
    void escalationManagers_unassigned_pmAndAdmins() {
        UUID projectId = UUID.randomUUID();
        UUID pmId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User adminUser = mock(User.class);
        when(adminUser.getId()).thenReturn(adminId);
        DailyProgressReport dpr = dpr(projectId, null);

        when(projectTeamService.resolvePmFor(projectId)).thenReturn(Optional.of(pmId));
        when(userRepository.findByRoleNamesAndEnabled(List.of("ADMIN")))
            .thenReturn(List.of(adminUser));

        Set<UUID> result = resolver.escalationManagers(dpr);

        assertThat(result).containsExactlyInAnyOrder(pmId, adminId);
    }

    // ── test 3: approver assigned but no manager → PM + admins ───────────────────

    @Test
    @DisplayName("3. approver assigned but getImmediateReporter empty → escalationManagers = PM + admins")
    void escalationManagers_approverWithNoManager_fallsToPmAndAdmins() {
        UUID projectId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID pmId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User adminUser = mock(User.class);
        when(adminUser.getId()).thenReturn(adminId);
        DailyProgressReport dpr = dpr(projectId, approverId);

        when(projectTeamService.getImmediateReporter(projectId, approverId))
            .thenReturn(Optional.empty());
        when(projectTeamService.resolvePmFor(projectId)).thenReturn(Optional.of(pmId));
        when(userRepository.findByRoleNamesAndEnabled(List.of("ADMIN")))
            .thenReturn(List.of(adminUser));

        Set<UUID> result = resolver.escalationManagers(dpr);

        assertThat(result).containsExactlyInAnyOrder(pmId, adminId);
    }
}
