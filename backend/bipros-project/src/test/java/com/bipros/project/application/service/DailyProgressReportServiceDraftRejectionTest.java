package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprAttachmentRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.DprVoiceNoteRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.infrastructure.storage.DprAttachmentStorageService;
import com.bipros.project.infrastructure.storage.VoiceNoteStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the activity-edit-status gate on DPR mutations: a DRAFT target activity must
 * reject the write with {@code ACTIVITY_DRAFT_DPR_REJECTED}; a LOCKED target proceeds
 * normally; a missing activity returns {@code ACTIVITY_NOT_FOUND}.
 *
 * <p>The gate uses a native SQL query via EntityManager — stubbed here through a single
 * mock {@link Query} whose {@code getResultList()} return is controlled per test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService DRAFT-activity gate")
class DailyProgressReportServiceDraftRejectionTest {

  @Mock private DailyProgressReportRepository dprRepository;
  @Mock private DprManpowerRepository manpowerRepository;
  @Mock private DprEquipmentRepository equipmentRepository;
  @Mock private DprMaterialRepository materialRepository;
  @Mock private com.bipros.project.domain.repository.DprSubContractorRepository subContractorRepository;
  @Mock private DprAttachmentRepository attachmentRepository;
  @Mock private DprVoiceNoteRepository voiceNoteRepository;
  @Mock private DprIssueRepository issueRepository;
  @Mock private DprAttachmentStorageService attachmentStorage;
  @Mock private VoiceNoteStorage voiceNoteStorage;
  @Mock private ProjectRepository projectRepository;
  @Mock private DailyActivityResourceOutputService ledgerService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private com.bipros.project.domain.repository.BoqItemRepository boqItemRepository;

  @Mock private EntityManager entityManager;
  @Mock private Query query;

  private DailyProgressReportService service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID activityId = UUID.randomUUID();
  private final UUID dprId = UUID.randomUUID();
  private final UUID supervisorId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
        attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
        eventPublisher, null, boqItemRepository);

    // Inject the mocked EntityManager — @PersistenceContext field, can't use @InjectMocks here
    // because the service also has @RequiredArgsConstructor for the other deps.
    ReflectionTestUtils.setField(service, "em", entityManager);

    // EntityManager chain: createNativeQuery(...).setParameter(...).getResultList() / executeUpdate()
    // The same mocked Query handles every call site. Per-test, only getResultList() return value
    // matters (it gates rejectIfActivityDraft); executeUpdate() defaults to 0 which is fine for
    // the rollup statements run after a successful create.
    lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    lenient().when(query.executeUpdate()).thenReturn(0);

    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    lenient().when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });
    lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any()))
        .thenReturn(BigDecimal.ZERO);
    lenient().when(dprRepository.findFirstByProjectIdAndReportDateAndActivityIdAndSupervisorUserId(
            any(), any(), any(), any())).thenReturn(Optional.empty());
    lenient().when(manpowerRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(materialRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any()))
        .thenReturn(List.of());
    lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(List.of());
    lenient().when(issueRepository.saveAll(any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("create rejects with ACTIVITY_DRAFT_DPR_REJECTED when activity is DRAFT")
  void createRejectsDraftActivity() {
    // SELECT edit_status, code returns DRAFT row
    when(query.getResultList())
        .thenReturn(java.util.Collections.singletonList(new Object[]{"DRAFT", "TEST-001"}));

    assertThatThrownBy(() -> service.create(projectId, createRequest()))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("ACTIVITY_DRAFT_DPR_REJECTED"));
    // No DPR row should ever be persisted.
    verify(dprRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  @DisplayName("create succeeds when activity is LOCKED (DPR persists)")
  void createSucceedsForLockedActivity() {
    // 1st getResultList() → rejectIfActivityDraft (activity status)
    // 2nd-4th getResultList() → ensureAssignmentsExist (manpower, equipment, material)
    // All child lists are empty because no DPR children exist in this test.
    when(query.getResultList())
        .thenReturn(java.util.Collections.singletonList(new Object[]{"LOCKED", "TEST-002"}))
        .thenReturn(List.of())
        .thenReturn(List.of())
        .thenReturn(List.of());

    assertThatCode(() -> service.create(projectId, createRequest()))
        .doesNotThrowAnyException();

    // DPR was saved — the gate let it through.
    verify(dprRepository).save(any(DailyProgressReport.class));
  }

  @Test
  @DisplayName("create rejects with ACTIVITY_NOT_FOUND when activity id resolves to nothing")
  void createRejectsWhenActivityMissing() {
    // SELECT returns empty list → activity doesn't exist.
    when(query.getResultList()).thenReturn(List.of());

    assertThatThrownBy(() -> service.create(projectId, createRequest()))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("ACTIVITY_NOT_FOUND"));
    verify(dprRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  @DisplayName("update rejects with ACTIVITY_DRAFT_DPR_REJECTED when target activity is DRAFT")
  void updateRejectsDraftActivity() {
    // The existing DPR points at activityId; update keeps it. The gate is checked AFTER find().
    DailyProgressReport existing = baseDpr();
    when(dprRepository.findById(dprId)).thenReturn(Optional.of(existing));
    when(query.getResultList())
        .thenReturn(java.util.Collections.singletonList(new Object[]{"DRAFT", "TEST-001"}));

    assertThatThrownBy(() -> service.update(projectId, dprId, updateRequest()))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("ACTIVITY_DRAFT_DPR_REJECTED"));
    // DPR row must not be re-persisted on a rejected update.
    verify(dprRepository, org.mockito.Mockito.never()).save(any());
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private CreateDailyProgressReportRequest createRequest() {
    return new CreateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 1), supervisorId, "Some Supervisor",
        null, null, activityId, "Test Activity", null, null, null, "Cum",
        new BigDecimal("10.0"), null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  private UpdateDailyProgressReportRequest updateRequest() {
    return new UpdateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 1), supervisorId, "Some Supervisor",
        null, null, activityId, "Test Activity", null, null, null, "Cum",
        new BigDecimal("10.0"), null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  private DailyProgressReport baseDpr() {
    DailyProgressReport d = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(LocalDate.of(2026, 5, 1))
        .supervisorUserId(supervisorId)
        .supervisorName("Some Supervisor")
        .activityId(activityId)
        .activityName("Test Activity")
        .unit("Cum")
        .qtyExecuted(new BigDecimal("10.0"))
        .build();
    d.setId(dprId);
    return d;
  }
}
