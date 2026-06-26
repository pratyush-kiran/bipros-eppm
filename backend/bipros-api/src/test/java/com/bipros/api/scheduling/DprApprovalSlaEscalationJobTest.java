package com.bipros.api.scheduling;

import com.bipros.api.notification.DprNotificationType;
import com.bipros.api.service.DprNotificationRecipientResolver;
import com.bipros.api.service.DprSlaConfig;
import com.bipros.common.notification.NotificationService;
import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;

@ExtendWith(MockitoExtension.class)
@DisplayName("DprApprovalSlaEscalationJob")
class DprApprovalSlaEscalationJobTest {

    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private ScheduledJobLeaseRepository leaseRepository;
    @Mock private DprSlaConfig slaConfig;
    @Mock private NotificationService notificationService;
    @Mock private DprNotificationRecipientResolver recipientResolver;

    private DprApprovalSlaEscalationJob job;

    @BeforeEach
    void setUp() {
        job = new DprApprovalSlaEscalationJob(dprRepository, leaseRepository, slaConfig, notificationService, recipientResolver);
    }

    // ─── helper ───────────────────────────────────────────────────────────────

    private DailyProgressReport buildDpr(UUID approverId, Instant submittedAt) {
        DailyProgressReport dpr = DailyProgressReport.builder()
            .projectId(UUID.randomUUID())
            .reportDate(LocalDate.of(2026, 6, 20))
            .supervisorName("Alice")
            .activityName("Road Laying")
            .unit("m")
            .qtyExecuted(BigDecimal.TEN)
            .approvalStatus(DprApprovalStatus.SUBMITTED)
            .submittedAt(submittedAt)
            .build();
        dpr.setAssignedApproverUserId(approverId);
        return dpr;
    }

    // ─── test 1: lease busy ───────────────────────────────────────────────────

    @Test
    @DisplayName("1. Lease busy (tryAcquire=0): finder and notifications are never called")
    void leaseBusy_shortCircuits() {
        when(leaseRepository.tryAcquire(any(), any(), any(), any())).thenReturn(0);

        job.run();

        verify(dprRepository, never()).findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(any(), any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    // ─── test 2: one overdue assigned DPR ────────────────────────────────────

    @Test
    @DisplayName("2. One overdue assigned DPR: approver + manager notified, escalatedAt set")
    void overdueAssignedDpr_notifiesApproverAndManager() {
        UUID approverId = UUID.randomUUID();
        UUID mgr = UUID.randomUUID();
        Instant submittedAt = Instant.now().minus(30, ChronoUnit.HOURS);

        DailyProgressReport dpr = buildDpr(approverId, submittedAt);

        when(leaseRepository.tryAcquire(any(), any(), any(), any())).thenReturn(1);
        when(slaConfig.slaHours()).thenReturn(24);
        when(dprRepository.findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(
            eq(DprApprovalStatus.SUBMITTED), any()))
            .thenReturn(List.of(dpr));
        when(recipientResolver.escalationManagers(dpr)).thenReturn(Set.of(mgr));

        job.run();

        verify(notificationService, times(1)).create(
            eq(approverId), eq(DprNotificationType.DPR_APPROVAL_OVERDUE_APPROVER),
            any(), any(), any(), any(), any());
        verify(notificationService, times(1)).create(
            eq(mgr), eq(DprNotificationType.DPR_APPROVAL_OVERDUE_ESCALATION),
            any(), any(), any(), any(), any());
        assertThat(dpr.getEscalatedAt()).isNotNull();
    }

    // ─── test 3: one overdue unassigned DPR ──────────────────────────────────

    @Test
    @DisplayName("3. One overdue unassigned DPR: no APPROVER notification; ESCALATION for each manager")
    void overdueUnassignedDpr_noApproverNotif_escalatesToManagers() {
        UUID pm = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Instant submittedAt = Instant.now().minus(30, ChronoUnit.HOURS);

        DailyProgressReport dpr = buildDpr(null, submittedAt);

        when(leaseRepository.tryAcquire(any(), any(), any(), any())).thenReturn(1);
        when(slaConfig.slaHours()).thenReturn(24);
        when(dprRepository.findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(
            eq(DprApprovalStatus.SUBMITTED), any()))
            .thenReturn(List.of(dpr));
        when(recipientResolver.escalationManagers(dpr)).thenReturn(Set.of(pm, admin));

        job.run();

        verify(notificationService, never()).create(
            any(), eq(DprNotificationType.DPR_APPROVAL_OVERDUE_APPROVER),
            any(), any(), any(), any(), any());
        verify(notificationService, times(1)).create(
            eq(pm), eq(DprNotificationType.DPR_APPROVAL_OVERDUE_ESCALATION),
            any(), any(), any(), any(), any());
        verify(notificationService, times(1)).create(
            eq(admin), eq(DprNotificationType.DPR_APPROVAL_OVERDUE_ESCALATION),
            any(), any(), any(), any(), any());
        assertThat(dpr.getEscalatedAt()).isNotNull();
    }

    // ─── test 4: slaHours consulted; finder called with SUBMITTED + cutoff ───

    @Test
    @DisplayName("4. slaConfig.slaHours() consulted; finder called with SUBMITTED and cutoff ≈ now-24h")
    void slaHoursConsulted_finderCalledWithCorrectArgs() {
        when(leaseRepository.tryAcquire(any(), any(), any(), any())).thenReturn(1);
        when(slaConfig.slaHours()).thenReturn(24);
        when(dprRepository.findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(any(), any()))
            .thenReturn(List.of());

        Instant before = Instant.now();
        job.run();
        Instant after = Instant.now();

        verify(slaConfig).slaHours();

        ArgumentCaptor<DprApprovalStatus> statusCaptor = ArgumentCaptor.forClass(DprApprovalStatus.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(dprRepository).findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(
            statusCaptor.capture(), cutoffCaptor.capture());

        assertThat(statusCaptor.getValue()).isEqualTo(DprApprovalStatus.SUBMITTED);

        // cutoff should be between (before - 24h - 1s) and (after - 24h + 1s)
        Instant expectedCutoffLow = before.minus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.SECONDS);
        Instant expectedCutoffHigh = after.minus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.SECONDS);
        assertThat(cutoffCaptor.getValue()).isBetween(expectedCutoffLow, expectedCutoffHigh);
    }
}
