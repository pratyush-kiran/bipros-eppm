package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DprSubContractorRow;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprSubContractor;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sub-contractor validation and assignment actuals-rollup tests for
 * {@link DailyProgressReportService}. Mirrors the mocking style of
 * {@code DailyProgressReportServiceDraftRejectionTest}: a single mocked
 * {@link EntityManager} backs every native SQL call, with per-test stubs of
 * {@link Query#getResultList()} keyed by the SQL string fragment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — sub-contractor validation & rollup")
class DailyProgressReportServiceSubContractorTest {

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
  @Mock private DailyActivityResourceOutputService ledgerService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private BoqItemRepository boqItemRepository;

  @Mock private EntityManager entityManager;

  private DailyProgressReportService service;

  /**
   * Stubbed result-lists keyed by SQL substring. A factory of {@link Query} mocks routes each
   * {@code createNativeQuery(sql)} call to a Query that returns the matching result list.
   */
  private final Map<String, List<?>> resultListBySql = new HashMap<>();

  private final UUID projectId = UUID.randomUUID();
  private final UUID dprId = UUID.randomUUID();
  private final UUID dprActivityId = UUID.randomUUID();
  private final UUID assignmentA = UUID.randomUUID();
  private final UUID masterA = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
        attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
        eventPublisher, null, boqItemRepository, null, null, null, null);
    ReflectionTestUtils.setField(service, "em", entityManager);

    // Route every native query to a Query mock that consults resultListBySql.
    lenient().when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      Query q = org.mockito.Mockito.mock(Query.class);
      lenient().when(q.setParameter(anyString(), any())).thenReturn(q);
      lenient().when(q.executeUpdate()).thenReturn(0);
      lenient().when(q.getResultList()).thenAnswer(x -> {
        for (Map.Entry<String, List<?>> e : resultListBySql.entrySet()) {
          if (sql.contains(e.getKey())) return e.getValue();
        }
        return List.of();
      });
      lenient().when(q.getSingleResult()).thenAnswer(x -> {
        for (Map.Entry<String, List<?>> e : resultListBySql.entrySet()) {
          if (sql.contains(e.getKey()) && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
      });
      return q;
    });

    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    lenient().when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });
    lenient().when(dprRepository.findById(dprId)).thenAnswer(inv -> Optional.of(baseDpr()));
    lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any()))
        .thenReturn(BigDecimal.ZERO);
    lenient().when(dprRepository.findFirstByProjectIdAndReportDateAndActivityIdAndSupervisorUserId(
            any(), any(), any(), any())).thenReturn(Optional.empty());

    lenient().when(manpowerRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(materialRepository.saveAll(any())).thenReturn(List.of());
    lenient().when(manpowerRepository.findByDprIdOrderByTradeAsc(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(any())).thenReturn(List.of());
    lenient().when(materialRepository.findByDprIdOrderByMaterialNameAsc(any())).thenReturn(List.of());

    lenient().when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(any()))
        .thenReturn(List.of());
    lenient().when(subContractorRepository.findByDprIdIn(any())).thenReturn(List.of());
    lenient().when(subContractorRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(subContractorRepository.sumQuantityByActivitySubContractorAssignmentIdApproved(any()))
        .thenReturn(BigDecimal.ZERO);

    lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.findByDprIdIn(any())).thenReturn(List.of());
    lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(List.of());
    lenient().when(issueRepository.findByDprIdIn(any())).thenReturn(List.of());
    lenient().when(issueRepository.saveAll(any())).thenReturn(List.of());
  }

  // ─── Tests ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("create throws SC_EXCEEDS_WORKDONE when sum(qty) > qtyExecuted")
  void createRejectsScSumExceedingWorkdone() {
    stubAssignmentLookup(assignmentA, dprActivityId, masterA, "Cum", new BigDecimal("4250"));
    CreateDailyProgressReportRequest req = createRequest(
        new BigDecimal("50"),
        List.of(scRow(assignmentA, new BigDecimal("80"))));   // sum = 80 > 50

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("SC_EXCEEDS_WORKDONE"));
  }

  @Test
  @DisplayName("create throws SC_DUPLICATE_ROW when two rows reference the same assignment")
  void createRejectsDuplicateAssignmentInOneDpr() {
    stubAssignmentLookup(assignmentA, dprActivityId, masterA, "Cum", new BigDecimal("4250"));
    CreateDailyProgressReportRequest req = createRequest(
        new BigDecimal("100"),
        List.of(scRow(assignmentA, new BigDecimal("10")),
                scRow(assignmentA, new BigDecimal("20"))));

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("SC_DUPLICATE_ROW"));
  }

  @Test
  @DisplayName("create throws SC_ASSIGNMENT_ACTIVITY_MISMATCH when assignment belongs to other activity")
  void createRejectsCrossActivityAssignment() {
    UUID otherActivity = UUID.randomUUID();
    stubAssignmentLookup(assignmentA, otherActivity, masterA, "Cum", new BigDecimal("4250"));
    CreateDailyProgressReportRequest req = createRequest(
        new BigDecimal("100"),
        List.of(scRow(assignmentA, new BigDecimal("10"))));

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("SC_ASSIGNMENT_ACTIVITY_MISMATCH"));
  }

  @Test
  @DisplayName("create throws SC_ASSIGNMENT_NOT_FOUND when assignment id is unknown")
  void createRejectsMissingAssignment() {
    // No assignment-lookup stub — getResultList returns empty for the SC query. The activity
    // gate must still be stubbed so the write proceeds past rejectIfActivityDraft.
    stubActivityGate();
    CreateDailyProgressReportRequest req = createRequest(
        new BigDecimal("100"),
        List.of(scRow(assignmentA, new BigDecimal("10"))));

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
            .isEqualTo("SC_ASSIGNMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("create persists valid SC row and triggers actuals recompute SUM query")
  void createValidScRowPersistsAndRecomputes() {
    stubAssignmentLookup(assignmentA, dprActivityId, masterA, "Cum", new BigDecimal("4250"));
    when(subContractorRepository.sumQuantityByActivitySubContractorAssignmentIdApproved(eq(assignmentA)))
        .thenReturn(new BigDecimal("30"));

    CreateDailyProgressReportRequest req = createRequest(
        new BigDecimal("100"),
        List.of(scRow(assignmentA, new BigDecimal("30"))));

    service.create(projectId, req);

    // The save of the DPR sub-contractor row.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DprSubContractor>> cap = ArgumentCaptor.forClass(List.class);
    verify(subContractorRepository).saveAll(cap.capture());
    assertThat(cap.getValue()).hasSize(1);
    DprSubContractor saved = cap.getValue().get(0);
    assertThat(saved.getActivitySubContractorAssignmentId()).isEqualTo(assignmentA);
    assertThat(saved.getQuantity()).isEqualByComparingTo("30");
    assertThat(saved.getSubContractorMasterId()).isEqualTo(masterA);

    // The recompute path: sumQuantity… for assignmentA must have been queried.
    verify(subContractorRepository, atLeastOnce())
        .sumQuantityByActivitySubContractorAssignmentIdApproved(assignmentA);
  }

  @Test
  @DisplayName("update removing the only SC row triggers recompute on the orphaned assignment")
  void updateRemovingRowRecomputesOrphanedAssignment() {
    stubActivityGate();
    // Existing DPR has one SC row pointing at assignmentA, qty=30.
    DprSubContractor existing = DprSubContractor.builder()
        .dprId(dprId)
        .subContractorMasterId(masterA)
        .activitySubContractorAssignmentId(assignmentA)
        .quantity(new BigDecimal("30"))
        .subContractorName("Acme")
        .build();
    existing.setId(UUID.randomUUID());
    when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(dprId))
        .thenReturn(List.of(existing));
    // After removal, the sum drops to zero.
    when(subContractorRepository.sumQuantityByActivitySubContractorAssignmentIdApproved(eq(assignmentA)))
        .thenReturn(BigDecimal.ZERO);

    // Update with empty subContractors list to remove the row.
    UpdateDailyProgressReportRequest req = updateRequest(new BigDecimal("100"), List.of());

    service.update(projectId, dprId, req);

    verify(subContractorRepository).deleteByDprId(dprId);
    // The orphaned assignment must have been recomputed (SUM call) after the delete.
    verify(subContractorRepository, atLeastOnce())
        .sumQuantityByActivitySubContractorAssignmentIdApproved(assignmentA);
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  /**
   * Stub the native lookup against
   * {@code SELECT activity_id, sub_contractor_master_id, work_type_name, unit, rate_per_unit
   *   FROM resource.activity_sub_contractor_assignments WHERE id = :id}.
   * The service treats an empty result-list as SC_ASSIGNMENT_NOT_FOUND; otherwise it reads
   * activityId from column 0 and the master id from column 1.
   */
  private void stubAssignmentLookup(UUID assignmentId, UUID activityId, UUID masterId,
                                    String unit, BigDecimal rate) {
    Object[] row = new Object[]{activityId, masterId, "Sub-contracting Work", unit, rate};
    resultListBySql.put(
        "SELECT activity_id, sub_contractor_master_id, work_type_name",
        List.<Object[]>of(row));
    // Recompute path also issues SELECT rate_per_unit FROM resource.activity_sub_contractor_assignments
    // — return the rate as a single BigDecimal column.
    resultListBySql.put(
        "SELECT rate_per_unit FROM resource.activity_sub_contractor_assignments",
        List.<Object>of(rate));
    // Master-name lookup stub — returns a non-empty list so the snapshot path uses the looked-up
    // name/code. java.util.Arrays.asList allows null elements; List.of does not.
    resultListBySql.put("FROM resource.sub_contractor_master WHERE id = :id",
        List.<Object[]>of(new Object[]{"Acme", "ACME-1"}));
    // Activity edit_status (rejectIfActivityDraft) — return LOCKED so the write proceeds.
    resultListBySql.put("SELECT a.edit_status, a.code", List.<Object[]>of(new Object[]{"LOCKED", "ACT-1"}));
  }

  /** Stub only the activity-not-DRAFT gate (so DPR write proceeds far enough to hit SC code). */
  private void stubActivityGate() {
    resultListBySql.put("SELECT a.edit_status, a.code", List.<Object[]>of(new Object[]{"LOCKED", "ACT-1"}));
  }

  private DprSubContractorRow scRow(UUID assignmentId, BigDecimal qty) {
    return new DprSubContractorRow(
        null, assignmentId, null, null, null, null, null, null, qty, null, null);
  }

  private CreateDailyProgressReportRequest createRequest(
      BigDecimal qtyExecuted, List<DprSubContractorRow> sub) {
    return new CreateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 1), null, "Supervisor",
        null, null, dprActivityId, "Test Activity", null, null, null, "Cum",
        qtyExecuted, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, sub, null);
  }

  private UpdateDailyProgressReportRequest updateRequest(
      BigDecimal qtyExecuted, List<DprSubContractorRow> sub) {
    return new UpdateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 1), null, "Supervisor",
        null, null, dprActivityId, "Test Activity", null, null, null, "Cum",
        qtyExecuted, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, sub, null);
  }

  private DailyProgressReport baseDpr() {
    DailyProgressReport d = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(LocalDate.of(2026, 5, 1))
        .activityId(dprActivityId)
        .activityName("Test Activity")
        .unit("Cum")
        .qtyExecuted(new BigDecimal("100"))
        .build();
    d.setId(dprId);
    return d;
  }
}
