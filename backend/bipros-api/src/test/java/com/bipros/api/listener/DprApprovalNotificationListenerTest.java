package com.bipros.api.listener;

import com.bipros.api.notification.DprNotificationType;
import com.bipros.api.service.DprNotificationRecipientResolver;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.notification.NotificationService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DprApprovalNotificationListener")
class DprApprovalNotificationListenerTest {

    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private NotificationService notificationService;
    @Mock private DprNotificationRecipientResolver recipientResolver;

    private DprApprovalNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new DprApprovalNotificationListener(dprRepository, notificationService, recipientResolver);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private DprSubmittedEvent event(UUID dprId, DprMutationType type) {
        return DprSubmittedEvent.withoutChildren(
            UUID.randomUUID(), dprId, LocalDate.now(), "Road Laying",
            "BOQ-01", BigDecimal.TEN, null, BigDecimal.ZERO,
            type, null);
    }

    private DailyProgressReport dpr(UUID dprId, UUID projectId, DprApprovalStatus status) {
        return DailyProgressReport.builder()
            .projectId(projectId)
            .reportDate(LocalDate.of(2026, 6, 20))
            .supervisorName("Alice")
            .activityName("Road Laying")
            .unit("m")
            .qtyExecuted(BigDecimal.TEN)
            .approvalStatus(status)
            .submittedAt(Instant.parse("2026-06-20T08:00:00Z"))
            .build();
    }

    // ── test 1: SUBMITTED + assigned approver → notification sent ─────────────────

    @Test
    @DisplayName("1. SUBMITTED + assigned approver: create called once with correct type and recipient")
    void submitted_assignedApprover_notifiesSingle() {
        UUID dprId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        DailyProgressReport dpr = dpr(dprId, projectId, DprApprovalStatus.SUBMITTED);
        dpr.setAssignedApproverUserId(approverId);
        dpr.setSubmittedAt(Instant.parse("2026-06-20T08:00:00Z"));

        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
        when(recipientResolver.arrivalRecipients(dpr)).thenReturn(Set.of(approverId));
        when(notificationService.existsSince(any(), any(), any(), any())).thenReturn(false);

        listener.onDpr(event(dprId, DprMutationType.CREATED));

        ArgumentCaptor<UUID> recipCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> relatedCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationService, times(1)).create(
            recipCaptor.capture(), typeCaptor.capture(),
            any(), any(), any(), any(), relatedCaptor.capture());

        assertThat(recipCaptor.getValue()).isEqualTo(approverId);
        assertThat(typeCaptor.getValue()).isEqualTo(DprNotificationType.DPR_SUBMITTED_FOR_APPROVAL);
        assertThat(relatedCaptor.getValue()).isEqualTo(dpr.getId());
    }

    // ── test 2: dedup — existsSince returns true → no create ─────────────────────

    @Test
    @DisplayName("2. SUBMITTED dedup: existsSince=true → create NOT called")
    void submitted_dedup_noNotification() {
        UUID dprId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        DailyProgressReport dpr = dpr(dprId, projectId, DprApprovalStatus.SUBMITTED);
        dpr.setAssignedApproverUserId(approverId);

        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
        when(recipientResolver.arrivalRecipients(dpr)).thenReturn(Set.of(approverId));
        when(notificationService.existsSince(eq(dpr.getId()),
            eq(DprNotificationType.DPR_SUBMITTED_FOR_APPROVAL), eq(approverId), any()))
            .thenReturn(true);

        listener.onDpr(event(dprId, DprMutationType.CREATED));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    // ── test 3: SUBMITTED + unassigned → 2 recipients (pm + admin) ──────────────

    @Test
    @DisplayName("3. SUBMITTED + unassigned: create called for each recipient")
    void submitted_unassigned_notifiesMultiple() {
        UUID dprId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID pmId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        DailyProgressReport dpr = dpr(dprId, projectId, DprApprovalStatus.SUBMITTED);

        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
        when(recipientResolver.arrivalRecipients(dpr)).thenReturn(Set.of(pmId, adminId));
        when(notificationService.existsSince(any(), any(), any(), any())).thenReturn(false);

        listener.onDpr(event(dprId, DprMutationType.CREATED));

        verify(notificationService, times(2)).create(
            any(), eq(DprNotificationType.DPR_SUBMITTED_FOR_APPROVAL),
            any(), any(), any(), any(), any());
    }

    // ── test 4: APPROVED → notify submitter ──────────────────────────────────────

    @Test
    @DisplayName("4. APPROVED: create called for submitter with DPR_APPROVED type")
    void approved_notifiesSubmitter() {
        UUID dprId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID submitterId = UUID.randomUUID();

        DailyProgressReport dpr = dpr(dprId, projectId, DprApprovalStatus.APPROVED);
        dpr.setSubmittedByUserId(submitterId);

        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

        listener.onDpr(event(dprId, DprMutationType.UPDATED));

        ArgumentCaptor<UUID> recipCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(1)).create(
            recipCaptor.capture(), typeCaptor.capture(),
            any(), any(), any(), any(), any());

        assertThat(recipCaptor.getValue()).isEqualTo(submitterId);
        assertThat(typeCaptor.getValue()).isEqualTo(DprNotificationType.DPR_APPROVED);
    }

    // ── test 5: REJECTED with reason → body contains reason ──────────────────────

    @Test
    @DisplayName("5. REJECTED: create called for submitter with DPR_REJECTED and reason in body")
    void rejected_notifiesSubmitterWithReason() {
        UUID dprId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID submitterId = UUID.randomUUID();

        DailyProgressReport dpr = dpr(dprId, projectId, DprApprovalStatus.REJECTED);
        dpr.setSubmittedByUserId(submitterId);
        dpr.setRejectionReason("Quantities don't match survey");

        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

        listener.onDpr(event(dprId, DprMutationType.UPDATED));

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(1)).create(
            eq(submitterId), typeCaptor.capture(),
            any(), bodyCaptor.capture(), any(), any(), any());

        assertThat(typeCaptor.getValue()).isEqualTo(DprNotificationType.DPR_REJECTED);
        assertThat(bodyCaptor.getValue()).contains("Quantities don't match survey");
    }

    // ── test 6: DELETED event → findById NOT called, no create ───────────────────

    @Test
    @DisplayName("6. DELETED event: short-circuits before findById and create")
    void deleted_skipsAll() {
        UUID dprId = UUID.randomUUID();

        listener.onDpr(event(dprId, DprMutationType.DELETED));

        verify(dprRepository, never()).findById(any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }
}
