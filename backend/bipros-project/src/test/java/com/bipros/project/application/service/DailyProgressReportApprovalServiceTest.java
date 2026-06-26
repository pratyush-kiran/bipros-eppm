package com.bipros.project.application.service;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.security.UserPermissionPort;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.DailyProgressReportResponse;
import com.bipros.project.application.dto.DprApprovalActionRequest;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalHistory;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprApprovalHistoryRepository;
import com.bipros.project.domain.repository.DprAttachmentRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.project.domain.repository.DprVoiceNoteRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.infrastructure.storage.DprAttachmentStorageService;
import com.bipros.project.infrastructure.storage.VoiceNoteStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for DPR approval workflow: approve / reject / revoke transitions.
 * Covers state-machine guards, authorization rules, field stamping, history append, and
 * recompute event publication.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — approval workflow")
class DailyProgressReportApprovalServiceTest {

  // ── repos ────────────────────────────────────────────────────────────────────
  @Mock private DailyProgressReportRepository dprRepository;
  @Mock private DprManpowerRepository manpowerRepository;
  @Mock private DprEquipmentRepository equipmentRepository;
  @Mock private DprMaterialRepository materialRepository;
  @Mock private DprSubContractorRepository subContractorRepository;
  @Mock private DprAttachmentRepository attachmentRepository;
  @Mock private DprVoiceNoteRepository voiceNoteRepository;
  @Mock private DprIssueRepository issueRepository;
  @Mock private DprAttachmentStorageService attachmentStorage;
  @Mock private VoiceNoteStorage voiceNoteStorage;
  @Mock private ProjectRepository projectRepository;
  @Mock private BoqItemRepository boqItemRepository;
  @Mock private DprApprovalHistoryRepository approvalHistoryRepository;

  // ── infra ────────────────────────────────────────────────────────────────────
  @Mock private DailyActivityResourceOutputService ledgerService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;

  // ── security ─────────────────────────────────────────────────────────────────
  @Mock private ProjectAccessGuard projectAccessGuard;
  @Mock private UserPermissionPort userPermissionPort;

  private DailyProgressReportService service;

  private final UUID projectId   = UUID.randomUUID();
  private final UUID dprId       = UUID.randomUUID();
  private final UUID approverId  = UUID.randomUUID();
  private final UUID submitterId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
        attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
        eventPublisher, null, boqItemRepository,
        projectAccessGuard, userPermissionPort, approvalHistoryRepository, null);

    // Default: child repos return empty lists so get() doesn't NPE.
    lenient().when(manpowerRepository.findByDprIdOrderByTradeAsc(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(any())).thenReturn(List.of());
    lenient().when(materialRepository.findByDprIdOrderByMaterialNameAsc(any())).thenReturn(List.of());
    lenient().when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    lenient().when(voiceNoteRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(List.of());
    lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any())).thenReturn(BigDecimal.ZERO);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** Build a DPR with the given status; submittedBy is always submitterId. */
  private DailyProgressReport dprWithStatus(DprApprovalStatus status) {
    DailyProgressReport d = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(LocalDate.of(2026, 6, 1))
        .supervisorName("Test Supervisor")
        .activityName("Earthwork")
        .unit("Cum")
        .qtyExecuted(BigDecimal.TEN)
        .build();
    d.setId(dprId);
    d.setApprovalStatus(status);
    d.setSubmittedByUserId(submitterId);
    d.setAssignedApproverUserId(approverId); // assigned to approverId by default
    return d;
  }

  /** Non-admin approver: has DPR.APPROVE, not the submitter, assigned to dpr. */
  private void setupNonAdminApprover() {
    // Non-null set → not admin
    when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(Set.of(projectId));
    when(projectAccessGuard.currentUserId()).thenReturn(approverId);
    when(userPermissionPort.hasPermission(approverId, "DPR.APPROVE")).thenReturn(true);
  }

  /** Admin: getAccessibleProjectIdsForCurrentUser() returns null. */
  private void setupAdmin(UUID adminId) {
    when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(null);
    lenient().when(projectAccessGuard.currentUserId()).thenReturn(adminId);
  }

  /** Wire the DPR in the repo so find() + saveAndFlush() both work. */
  private void stubDpr(DailyProgressReport dpr) {
    when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
    lenient().when(dprRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  // ═════════════════════════════════════════════════════════════════════════════
  // Approve transition
  // ═════════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("approve()")
  class ApproveTests {

    @Test
    @DisplayName("SUBMITTED→APPROVED: sets approvedBy/At, status, saves, appends history, publishes event")
    void approveSubmittedDpr() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      setupNonAdminApprover();

      DailyProgressReportResponse resp = service.approve(projectId, dprId, new DprApprovalActionRequest(null));

      assertThat(dpr.getApprovalStatus()).isEqualTo(DprApprovalStatus.APPROVED);
      assertThat(dpr.getApprovedByUserId()).isEqualTo(approverId);
      assertThat(dpr.getApprovedAt()).isNotNull();

      verify(dprRepository).saveAndFlush(dpr);

      ArgumentCaptor<DprApprovalHistory> histCap = ArgumentCaptor.forClass(DprApprovalHistory.class);
      verify(approvalHistoryRepository).save(histCap.capture());
      DprApprovalHistory hist = histCap.getValue();
      assertThat(hist.getDprId()).isEqualTo(dprId);
      assertThat(hist.getFromStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
      assertThat(hist.getToStatus()).isEqualTo(DprApprovalStatus.APPROVED);
      assertThat(hist.getActorUserId()).isEqualTo(approverId);

      ArgumentCaptor<DprSubmittedEvent> evCap = ArgumentCaptor.forClass(DprSubmittedEvent.class);
      verify(eventPublisher, times(1)).publishEvent(evCap.capture());
      assertThat(evCap.getValue().eventType()).isEqualTo(DprMutationType.UPDATED);

      assertThat(resp).isNotNull();
    }

    @Test
    @DisplayName("approve when APPROVED → DPR_INVALID_TRANSITION")
    void approveAlreadyApproved() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessageContaining("APPROVED");

      verify(dprRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("approve when DRAFT → DPR_INVALID_TRANSITION")
    void approveWhenDraft() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.DRAFT);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("approve when null status (treated as DRAFT) → DPR_INVALID_TRANSITION")
    void approveWhenNullStatus() {
      DailyProgressReport dpr = dprWithStatus(null);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_INVALID_TRANSITION");
    }
  }

  // ═════════════════════════════════════════════════════════════════════════════
  // Reject transition
  // ═════════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("reject()")
  class RejectTests {

    @Test
    @DisplayName("SUBMITTED→REJECTED: sets rejectedBy/At/reason, saves, appends history, publishes event")
    void rejectSubmittedDpr() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      setupNonAdminApprover();

      service.reject(projectId, dprId, new DprApprovalActionRequest("Insufficient data"));

      assertThat(dpr.getApprovalStatus()).isEqualTo(DprApprovalStatus.REJECTED);
      assertThat(dpr.getRejectedByUserId()).isEqualTo(approverId);
      assertThat(dpr.getRejectedAt()).isNotNull();
      assertThat(dpr.getRejectionReason()).isEqualTo("Insufficient data");

      verify(dprRepository).saveAndFlush(dpr);

      ArgumentCaptor<DprApprovalHistory> histCap = ArgumentCaptor.forClass(DprApprovalHistory.class);
      verify(approvalHistoryRepository).save(histCap.capture());
      assertThat(histCap.getValue().getFromStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
      assertThat(histCap.getValue().getToStatus()).isEqualTo(DprApprovalStatus.REJECTED);
      assertThat(histCap.getValue().getReason()).isEqualTo("Insufficient data");

      verify(eventPublisher, times(1)).publishEvent(any(DprSubmittedEvent.class));
    }

    @Test
    @DisplayName("reject without reason → DPR_REASON_REQUIRED")
    void rejectWithoutReason() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.reject(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_REASON_REQUIRED");

      verify(dprRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("reject with blank reason → DPR_REASON_REQUIRED")
    void rejectWithBlankReason() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.reject(projectId, dprId, new DprApprovalActionRequest("  ")))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_REASON_REQUIRED");
    }

    @Test
    @DisplayName("reject when APPROVED → DPR_INVALID_TRANSITION")
    void rejectWhenApproved() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.reject(projectId, dprId, new DprApprovalActionRequest("reason")))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_INVALID_TRANSITION");
    }
  }

  // ═════════════════════════════════════════════════════════════════════════════
  // Revoke transition
  // ═════════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("revoke()")
  class RevokeTests {

    @Test
    @DisplayName("APPROVED→REJECTED: sets rejectedBy/At, defaulted reason, saves, appends history, publishes event")
    void revokeApprovedDpr() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      stubDpr(dpr);
      setupNonAdminApprover();

      service.revoke(projectId, dprId, new DprApprovalActionRequest(null));

      assertThat(dpr.getApprovalStatus()).isEqualTo(DprApprovalStatus.REJECTED);
      assertThat(dpr.getRejectedByUserId()).isEqualTo(approverId);
      assertThat(dpr.getRejectedAt()).isNotNull();
      assertThat(dpr.getRejectionReason()).isEqualTo("Approval revoked");

      verify(dprRepository).saveAndFlush(dpr);

      ArgumentCaptor<DprApprovalHistory> histCap = ArgumentCaptor.forClass(DprApprovalHistory.class);
      verify(approvalHistoryRepository).save(histCap.capture());
      assertThat(histCap.getValue().getFromStatus()).isEqualTo(DprApprovalStatus.APPROVED);
      assertThat(histCap.getValue().getToStatus()).isEqualTo(DprApprovalStatus.REJECTED);

      verify(eventPublisher, times(1)).publishEvent(any(DprSubmittedEvent.class));
    }

    @Test
    @DisplayName("revoke with custom reason uses that reason")
    void revokeWithCustomReason() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      stubDpr(dpr);
      setupNonAdminApprover();

      service.revoke(projectId, dprId, new DprApprovalActionRequest("Error in submission"));

      assertThat(dpr.getRejectionReason()).isEqualTo("Error in submission");
    }

    @Test
    @DisplayName("revoke when SUBMITTED → DPR_INVALID_TRANSITION")
    void revokeWhenSubmitted() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      setupNonAdminApprover();

      assertThatThrownBy(() -> service.revoke(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_INVALID_TRANSITION");

      verify(dprRepository, never()).saveAndFlush(any());
    }
  }

  // ═════════════════════════════════════════════════════════════════════════════
  // Authorization
  // ═════════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Authorization")
  class AuthorizationTests {

    @Test
    @DisplayName("assigned approver (non-admin, has DPR.APPROVE, not submitter) → allowed")
    void assignedApproverAllowed() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setAssignedApproverUserId(approverId);
      stubDpr(dpr);
      setupNonAdminApprover();

      // Should not throw
      service.approve(projectId, dprId, new DprApprovalActionRequest(null));

      verify(dprRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("admin (getAccessibleProjectIds returns null) → allowed even if assigned to someone else")
    void adminAllowedEvenIfAssignedToOther() {
      UUID someOtherApprover = UUID.randomUUID();
      UUID adminId = UUID.randomUUID();
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setAssignedApproverUserId(someOtherApprover); // not admin
      stubDpr(dpr);
      setupAdmin(adminId);

      // Should not throw
      service.approve(projectId, dprId, new DprApprovalActionRequest(null));

      verify(dprRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("admin → allowed even if admin is the submitter (no separation-of-duties for admin)")
    void adminAllowedEvenIfSubmitter() {
      UUID adminId = UUID.randomUUID();
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setSubmittedByUserId(adminId); // admin submitted this
      dpr.setAssignedApproverUserId(adminId);
      stubDpr(dpr);
      setupAdmin(adminId);

      // Should not throw
      service.approve(projectId, dprId, new DprApprovalActionRequest(null));

      verify(dprRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("unassigned DPR + caller has DPR.APPROVE → allowed")
    void unassignedDprApproverAllowed() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setAssignedApproverUserId(null); // unassigned
      stubDpr(dpr);
      when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(Set.of(projectId));
      when(projectAccessGuard.currentUserId()).thenReturn(approverId);
      when(userPermissionPort.hasPermission(approverId, "DPR.APPROVE")).thenReturn(true);

      // Should not throw — submitterId != approverId and dpr is unassigned
      service.approve(projectId, dprId, new DprApprovalActionRequest(null));

      verify(dprRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("caller lacks DPR.APPROVE → AccessDeniedException")
    void noPermissionDenied() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      stubDpr(dpr);
      when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(Set.of(projectId));
      when(projectAccessGuard.currentUserId()).thenReturn(approverId);
      when(userPermissionPort.hasPermission(approverId, "DPR.APPROVE")).thenReturn(false);

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(AccessDeniedException.class);

      verify(dprRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("submitter who holds DPR.APPROVE (non-admin) → AccessDeniedException (self-approval blocked)")
    void submitterBlockedFromApprovingOwnDpr() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setAssignedApproverUserId(submitterId); // submitter is also the assigned approver
      stubDpr(dpr);
      when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(Set.of(projectId));
      when(projectAccessGuard.currentUserId()).thenReturn(submitterId);
      when(userPermissionPort.hasPermission(submitterId, "DPR.APPROVE")).thenReturn(true);

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("own");

      verify(dprRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("non-admin caller, DPR assigned to a DIFFERENT approver → AccessDeniedException")
    void differentApproverBlocked() {
      UUID differentApprover = UUID.randomUUID();
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      dpr.setAssignedApproverUserId(differentApprover);
      stubDpr(dpr);
      when(projectAccessGuard.getAccessibleProjectIdsForCurrentUser()).thenReturn(Set.of(projectId));
      when(projectAccessGuard.currentUserId()).thenReturn(approverId); // not differentApprover
      when(userPermissionPort.hasPermission(approverId, "DPR.APPROVE")).thenReturn(true);

      assertThatThrownBy(() -> service.approve(projectId, dprId, new DprApprovalActionRequest(null)))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("another approver");

      verify(dprRepository, never()).saveAndFlush(any());
    }
  }

  // ═════════════════════════════════════════════════════════════════════════════
  // Locked-edit guard (T10)
  // ═════════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Locked-edit guard — APPROVED DPRs block update() and delete()")
  class LockedEditGuardTests {

    /**
     * Build a minimal UpdateDailyProgressReportRequest with just enough fields to reach the
     * guard check. The guard fires before any field mutation so null fields are fine here.
     */
    private com.bipros.project.application.dto.UpdateDailyProgressReportRequest minimalUpdateReq() {
      return new com.bipros.project.application.dto.UpdateDailyProgressReportRequest(
          java.time.LocalDate.of(2026, 6, 1),
          null, "Supervisor", null, null, null,
          "Earthwork", null, null, null, "Cum",
          BigDecimal.TEN, null, null,
          null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null);
    }

    @Test
    @DisplayName("update() on APPROVED DPR → DPR_LOCKED BusinessRuleException")
    void updateApprovedDprThrowsLocked() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

      assertThatThrownBy(() -> service.update(projectId, dprId, minimalUpdateReq()))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_LOCKED");

      verify(dprRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete() on APPROVED DPR → DPR_LOCKED BusinessRuleException")
    void deleteApprovedDprThrowsLocked() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.APPROVED);
      when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

      assertThatThrownBy(() -> service.delete(projectId, dprId))
          .isInstanceOf(BusinessRuleException.class)
          .extracting("ruleCode").isEqualTo("DPR_LOCKED");

      verify(dprRepository, never()).delete(any());
    }

    @Test
    @DisplayName("update() on SUBMITTED DPR does NOT throw DPR_LOCKED")
    void updateSubmittedDprDoesNotThrowLocked() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.SUBMITTED);
      when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
      // stub the activity-draft check (native query) — we don't care about the result, just
      // that no DPR_LOCKED is thrown. Let it proceed as far as it goes.
      lenient().when(dprRepository.save(any())).thenReturn(dpr);
      lenient().when(manpowerRepository.findByDprIdOrderByTradeAsc(any())).thenReturn(List.of());
      lenient().when(equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(any())).thenReturn(List.of());
      lenient().when(materialRepository.findByDprIdOrderByMaterialNameAsc(any())).thenReturn(List.of());
      lenient().when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(any())).thenReturn(List.of());
      lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(List.of());
      lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
      lenient().when(voiceNoteRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

      // Should throw something other than DPR_LOCKED (activity check / null pointer from
      // unenriched stubs is fine — we only care the lock is NOT the cause).
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        try {
          service.update(projectId, dprId, minimalUpdateReq());
        } catch (BusinessRuleException e) {
          if ("DPR_LOCKED".equals(e.getRuleCode())) throw e; // re-throw only if it's the lock
        } catch (Exception ignored) {
          // any other exception means the lock didn't fire — test passes
        }
      });
    }

    @Test
    @DisplayName("update() on REJECTED DPR does NOT throw DPR_LOCKED")
    void updateRejectedDprDoesNotThrowLocked() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.REJECTED);
      when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        try {
          service.update(projectId, dprId, minimalUpdateReq());
        } catch (BusinessRuleException e) {
          if ("DPR_LOCKED".equals(e.getRuleCode())) throw e;
        } catch (Exception ignored) {}
      });
    }

    @Test
    @DisplayName("update() on DRAFT DPR does NOT throw DPR_LOCKED")
    void updateDraftDprDoesNotThrowLocked() {
      DailyProgressReport dpr = dprWithStatus(DprApprovalStatus.DRAFT);
      when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));

      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        try {
          service.update(projectId, dprId, minimalUpdateReq());
        } catch (BusinessRuleException e) {
          if ("DPR_LOCKED".equals(e.getRuleCode())) throw e;
        } catch (Exception ignored) {}
      });
    }
  }
}
