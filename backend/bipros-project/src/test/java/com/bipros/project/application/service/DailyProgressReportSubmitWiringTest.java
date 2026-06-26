package com.bipros.project.application.service;

import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.security.UserPermissionPort;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.domain.model.DailyProgressReport;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for the DPR submit-wiring in {@link DailyProgressReportService}:
 * resolveApprover, submittedAt stamp, submittedByUserId, status coercion, and escalatedAt reset.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — submit wiring (N1+N2)")
class DailyProgressReportSubmitWiringTest {

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

  // ── security + team ──────────────────────────────────────────────────────────
  @Mock private ProjectAccessGuard projectAccessGuard;
  @Mock private UserPermissionPort userPermissionPort;
  @Mock private ProjectTeamService projectTeamService;

  private DailyProgressReportService service;

  private final UUID projectId   = UUID.randomUUID();
  private final UUID dprId       = UUID.randomUUID();
  private final UUID submitterId = UUID.randomUUID();
  private final UUID approverId  = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
        attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
        eventPublisher, null, boqItemRepository,
        projectAccessGuard, userPermissionPort, approvalHistoryRepository, projectTeamService);

    // Default stubs — lenient so unused stubs don't trip UnnecessaryStubbingException.
    lenient().when(projectRepository.existsById(any())).thenReturn(true);
    lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    lenient().when(manpowerRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(materialRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    lenient().when(voiceNoteRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(List.of());
    lenient().when(issueRepository.saveAll(any())).thenReturn(List.of());

    // Default: current user = submitterId; approver resolved to approverId.
    lenient().when(projectAccessGuard.currentUserId()).thenReturn(submitterId);
    lenient().when(projectTeamService.resolveApprover(any(), any())).thenReturn(Optional.of(approverId));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test 1: create with approvalStatus=SUBMITTED → stamp submittedAt, submittedByUserId, assignedApproverUserId
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("create with SUBMITTED status → submittedAt set, submittedByUserId = currentUserId, assignedApproverUserId = resolveApprover result")
  void createSubmitted_stampsApprovalFields() {
    when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });

    CreateDailyProgressReportRequest req = minimalCreate(DprApprovalStatus.SUBMITTED);

    service.create(projectId, req);

    // Capture the entity passed to save()
    org.mockito.ArgumentCaptor<DailyProgressReport> captor =
        org.mockito.ArgumentCaptor.forClass(DailyProgressReport.class);
    verify(dprRepository).save(captor.capture());
    DailyProgressReport saved = captor.getValue();

    assertThat(saved.getApprovalStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
    assertThat(saved.getSubmittedAt()).isNotNull();
    assertThat(saved.getSubmittedByUserId()).isEqualTo(submitterId);
    assertThat(saved.getAssignedApproverUserId()).isEqualTo(approverId);

    verify(projectTeamService).resolveApprover(projectId, submitterId);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test 2: create with approvalStatus=APPROVED → coerced to SUBMITTED, transition applied
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("create with client approvalStatus=APPROVED → coerced to SUBMITTED, transition fires")
  void createWithApprovedStatus_coercedToSubmitted() {
    when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });

    CreateDailyProgressReportRequest req = minimalCreate(DprApprovalStatus.APPROVED);

    service.create(projectId, req);

    org.mockito.ArgumentCaptor<DailyProgressReport> captor =
        org.mockito.ArgumentCaptor.forClass(DailyProgressReport.class);
    verify(dprRepository).save(captor.capture());
    DailyProgressReport saved = captor.getValue();

    assertThat(saved.getApprovalStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
    assertThat(saved.getSubmittedAt()).isNotNull();
    assertThat(saved.getSubmittedByUserId()).isEqualTo(submitterId);
    assertThat(saved.getAssignedApproverUserId()).isEqualTo(approverId);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test 3: update REJECTED→SUBMITTED (resubmit) → re-stamp + re-resolve + clear escalatedAt
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("update REJECTED→SUBMITTED → submittedAt re-stamped, escalatedAt cleared, approver re-resolved")
  void updateRejectedToSubmitted_reSubmitTransition() {
    Instant oldSubmittedAt = Instant.now().minusSeconds(3600);
    Instant oldEscalatedAt = Instant.now().minusSeconds(1800);

    DailyProgressReport existing = dprWithStatus(DprApprovalStatus.REJECTED);
    existing.setSubmittedAt(oldSubmittedAt);
    existing.setEscalatedAt(oldEscalatedAt);
    existing.setSubmittedByUserId(submitterId);

    when(dprRepository.findById(dprId)).thenReturn(Optional.of(existing));
    lenient().when(dprRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(manpowerRepository.findByDprIdOrderByTradeAsc(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(any())).thenReturn(List.of());
    lenient().when(materialRepository.findByDprIdOrderByMaterialNameAsc(any())).thenReturn(List.of());

    service.update(projectId, dprId, minimalUpdate(DprApprovalStatus.SUBMITTED));

    assertThat(existing.getApprovalStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
    assertThat(existing.getSubmittedAt()).isAfter(oldSubmittedAt);
    assertThat(existing.getEscalatedAt()).isNull();
    assertThat(existing.getSubmittedByUserId()).isEqualTo(submitterId);
    assertThat(existing.getAssignedApproverUserId()).isEqualTo(approverId);

    verify(projectTeamService).resolveApprover(projectId, submitterId);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test 4: update SUBMITTED→SUBMITTED (edit, no status change) → no re-stamp, no re-resolve
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("update SUBMITTED→SUBMITTED (no status change) → submittedAt preserved, resolveApprover NOT called, escalatedAt preserved")
  void updateSubmittedToSubmitted_noReStamp() {
    Instant originalSubmittedAt = Instant.now().minusSeconds(3600);
    Instant originalEscalatedAt = Instant.now().minusSeconds(1800);

    DailyProgressReport existing = dprWithStatus(DprApprovalStatus.SUBMITTED);
    existing.setSubmittedAt(originalSubmittedAt);
    existing.setEscalatedAt(originalEscalatedAt);
    existing.setSubmittedByUserId(submitterId);
    existing.setAssignedApproverUserId(approverId);

    when(dprRepository.findById(dprId)).thenReturn(Optional.of(existing));
    lenient().when(dprRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(manpowerRepository.findByDprIdOrderByTradeAsc(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(any())).thenReturn(List.of());
    lenient().when(materialRepository.findByDprIdOrderByMaterialNameAsc(any())).thenReturn(List.of());

    service.update(projectId, dprId, minimalUpdate(DprApprovalStatus.SUBMITTED));

    assertThat(existing.getSubmittedAt()).isEqualTo(originalSubmittedAt);
    assertThat(existing.getEscalatedAt()).isEqualTo(originalEscalatedAt);

    verify(projectTeamService, never()).resolveApprover(any(), any());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test 5: unassigned — resolveApprover returns empty → assignedApproverUserId null, no exception
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("resolveApprover returns empty → assignedApproverUserId null, no exception")
  void createSubmitted_noApproverResolved_assignedNull() {
    when(projectTeamService.resolveApprover(any(), any())).thenReturn(Optional.empty());
    when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });

    CreateDailyProgressReportRequest req = minimalCreate(DprApprovalStatus.SUBMITTED);

    service.create(projectId, req);

    org.mockito.ArgumentCaptor<DailyProgressReport> captor =
        org.mockito.ArgumentCaptor.forClass(DailyProgressReport.class);
    verify(dprRepository).save(captor.capture());
    DailyProgressReport saved = captor.getValue();

    assertThat(saved.getApprovalStatus()).isEqualTo(DprApprovalStatus.SUBMITTED);
    assertThat(saved.getAssignedApproverUserId()).isNull();
    assertThat(saved.getSubmittedAt()).isNotNull();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

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
    return d;
  }

  /** Minimal create request with only the required fields + the given approvalStatus. */
  private CreateDailyProgressReportRequest minimalCreate(DprApprovalStatus approvalStatus) {
    return new CreateDailyProgressReportRequest(
        LocalDate.of(2026, 6, 1),
        null,           // supervisorUserId
        "Supervisor",   // supervisorName
        null, null,     // chainageFromM, chainageToM
        null,           // activityId — null so rejectIfActivityDraft skips (requires em too)
        "Earthwork",    // activityName
        null,           // wbsNodeId
        null,           // boqItemId
        null,           // boqItemNo
        "Cum",          // unit
        BigDecimal.TEN, // qtyExecuted
        null,           // weatherCondition
        null,           // remarks
        null,           // side
        null,           // landmark
        null,           // startTime
        null,           // endTime
        null,           // shift
        approvalStatus, // approvalStatus
        null,           // contractorName
        null,           // delayReason
        null,           // safetyObservation
        null,           // safetyIncidentType
        null,           // manpower
        null,           // equipment
        null,           // materials
        null,           // subContractors
        null            // issues
    );
  }

  /** Minimal update request with only required fields + the given approvalStatus. */
  private UpdateDailyProgressReportRequest minimalUpdate(DprApprovalStatus approvalStatus) {
    return new UpdateDailyProgressReportRequest(
        LocalDate.of(2026, 6, 1),
        null,           // supervisorUserId
        "Supervisor",   // supervisorName
        null, null,     // chainageFromM, chainageToM
        null,           // activityId
        "Earthwork",    // activityName
        null,           // wbsNodeId
        null,           // boqItemId
        null,           // boqItemNo
        "Cum",          // unit
        BigDecimal.TEN, // qtyExecuted
        null,           // weatherCondition
        null,           // remarks
        null,           // side
        null,           // landmark
        null,           // startTime
        null,           // endTime
        null,           // shift
        approvalStatus, // approvalStatus
        null,           // contractorName
        null,           // delayReason
        null,           // safetyObservation
        null,           // safetyIncidentType
        null,           // manpower
        null,           // equipment
        null,           // materials
        null,           // subContractors
        null            // issues
    );
  }
}
