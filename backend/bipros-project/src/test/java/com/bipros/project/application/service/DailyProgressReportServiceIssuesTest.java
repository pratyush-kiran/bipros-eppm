package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.DprIssueRow;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
 * Issue merge-by-id lifecycle in DailyProgressReportService.update — diverges deliberately
 * from the full-replacement pattern of manpower / equipment / material because issue rows
 * carry status, opened_at, and ClickHouse _version lineage that mustn't be lost on re-save.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — DPR-issue merge-by-id lifecycle")
class DailyProgressReportServiceIssuesTest {

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

    private DailyProgressReportService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID dprId = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();
    private final UUID supervisorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DailyProgressReportService(
                dprRepository, manpowerRepository, equipmentRepository, materialRepository,
                subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
                attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
                eventPublisher, null, boqItemRepository);
        lenient().when(subContractorRepository.findByDprIdOrderBySubContractorNameAsc(any()))
                .thenReturn(java.util.List.of());
        lenient().when(subContractorRepository.findByDprIdIn(any())).thenReturn(java.util.List.of());
        lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
        lenient().when(dprRepository.findById(dprId)).thenReturn(Optional.of(baseDpr()));
        lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(dprRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(manpowerRepository.saveAll(any())).thenReturn(List.of());
        lenient().when(equipmentRepository.saveAll(any())).thenReturn(List.of());
        lenient().when(materialRepository.saveAll(any())).thenReturn(List.of());
        lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(issueRepository.saveAll(any())).thenAnswer(inv -> {
            Collection<DprIssue> in = inv.getArgument(0);
            List<DprIssue> out = new ArrayList<>();
            for (DprIssue i : in) {
                if (i.getId() == null) i.setId(UUID.randomUUID());
                out.add(i);
            }
            return out;
        });
    }

    @Test
    @DisplayName("update inserts new issue with snapshot of parent DPR's supervisor + activity")
    void insertStampsParentContext() {
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(List.of());

        DprIssueRow incoming = new DprIssueRow(
                null, null, null, null, null,
                "Material shortage", "Aggregate truck broke down",
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN,
                null, null, null, null, null, null, null, null, null);

        service.update(projectId, dprId, request(List.of(incoming)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DprIssue>> cap = ArgumentCaptor.forClass(List.class);
        verify(issueRepository).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        DprIssue saved = cap.getValue().get(0);
        assertThat(saved.getDprId()).isEqualTo(dprId);
        assertThat(saved.getProjectId()).isEqualTo(projectId);
        assertThat(saved.getActivityId()).isEqualTo(activityId);
        assertThat(saved.getActivityName()).isEqualTo("Bench Cutting");
        assertThat(saved.getSupervisorUserId()).isEqualTo(supervisorId);
        assertThat(saved.getSupervisorName()).isEqualTo("Mohd Ismaila");
        assertThat(saved.getAssignedToUserId()).isEqualTo(supervisorId); // defaults to supervisor
        assertThat(saved.getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(saved.getOpenedAt()).isNotNull();
        assertThat(saved.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("update preserves opened_at and id when an existing issue is re-submitted")
    void updateInPlace() {
        UUID issueId = UUID.randomUUID();
        Instant openedYesterday = Instant.now().minusSeconds(86_400);
        DprIssue existing = baseIssue(issueId, openedYesterday, IssueStatus.OPEN);
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(List.of(existing));

        DprIssueRow incoming = new DprIssueRow(
                issueId, null, null, null, null,
                "Material shortage (updated title)", null,
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.CRITICAL, IssueStatus.IN_PROGRESS,
                supervisorId, "Mohd Ismaila", supervisorId, "Mohd Ismaila",
                null, null, null, supervisorId, supervisorId);

        service.update(projectId, dprId, request(List.of(incoming)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DprIssue>> cap = ArgumentCaptor.forClass(List.class);
        verify(issueRepository).saveAll(cap.capture());
        DprIssue saved = cap.getValue().get(0);
        assertThat(saved.getId()).isEqualTo(issueId);
        assertThat(saved.getOpenedAt()).isEqualTo(openedYesterday); // preserved
        assertThat(saved.getSeverity()).isEqualTo(IssueSeverity.CRITICAL);
        assertThat(saved.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(issueRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("update auto-stamps resolved_at on terminal transition and clears on reopen")
    void resolvedAtAutoManaged() {
        UUID issueId = UUID.randomUUID();
        Instant opened = Instant.now().minusSeconds(7_200);

        // Phase 1: OPEN → RESOLVED (resolved_at should be set)
        DprIssue existing = baseIssue(issueId, opened, IssueStatus.OPEN);
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(List.of(existing));
        DprIssueRow toResolve = new DprIssueRow(
                issueId, null, null, null, null,
                "Material shortage", null,
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.RESOLVED,
                null, null, null, null, null, null, "Truck back, work resumed", null, null);

        service.update(projectId, dprId, request(List.of(toResolve)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DprIssue>> cap = ArgumentCaptor.forClass(List.class);
        verify(issueRepository, times(1)).saveAll(cap.capture());
        DprIssue saved = cap.getValue().get(0);
        assertThat(saved.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(saved.getResolvedAt()).isNotNull();

        // Phase 2: RESOLVED → IN_PROGRESS (resolved_at should be cleared)
        DprIssue resolved = baseIssue(issueId, opened, IssueStatus.RESOLVED);
        resolved.setResolvedAt(Instant.now());
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(List.of(resolved));
        DprIssueRow toReopen = new DprIssueRow(
                issueId, null, null, null, null,
                "Material shortage", null,
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.IN_PROGRESS,
                null, null, null, null, null, null, null, null, null);

        service.update(projectId, dprId, request(List.of(toReopen)));

        verify(issueRepository, times(2)).saveAll(cap.capture());
        DprIssue reopened = cap.getValue().get(0);
        assertThat(reopened.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(reopened.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("update deletes rows that are present in DB but absent from payload")
    void deleteOmittedRows() {
        UUID keepId = UUID.randomUUID();
        UUID dropId = UUID.randomUUID();
        Instant now = Instant.now();
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(Arrays.asList(
                baseIssue(keepId, now, IssueStatus.OPEN),
                baseIssue(dropId, now, IssueStatus.OPEN)));

        DprIssueRow stay = new DprIssueRow(
                keepId, null, null, null, null,
                "still relevant", null, IssueCategory.OTHER, IssueSeverity.LOW, IssueStatus.OPEN,
                null, null, null, null, null, null, null, null, null);

        service.update(projectId, dprId, request(List.of(stay)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> delCap = ArgumentCaptor.forClass(List.class);
        verify(issueRepository).deleteAllByIdInBatch(delCap.capture());
        assertThat(delCap.getValue()).containsExactly(dropId);
    }

    @Test
    @DisplayName("empty list clears all issues for the DPR (full delete)")
    void emptyListClearsAll() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Instant now = Instant.now();
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(Arrays.asList(
                baseIssue(idA, now, IssueStatus.OPEN),
                baseIssue(idB, now, IssueStatus.RESOLVED)));

        service.update(projectId, dprId, request(List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> delCap = ArgumentCaptor.forClass(List.class);
        verify(issueRepository).deleteAllByIdInBatch(delCap.capture());
        assertThat(delCap.getValue()).containsExactlyInAnyOrder(idA, idB);
        verify(issueRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("update rejects an id not associated with this DPR (409 via BusinessRuleException)")
    void rejectsForeignId() {
        UUID strangerId = UUID.randomUUID();
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(dprId)).thenReturn(List.of());

        DprIssueRow rogue = new DprIssueRow(
                strangerId, null, null, null, null,
                "title", null, IssueCategory.OTHER, IssueSeverity.LOW, IssueStatus.OPEN,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(projectId, dprId, request(List.of(rogue))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
                        .isEqualTo("DPR_ISSUE_NOT_FOUND"));
    }

    @Test
    @DisplayName("delete cascades issueRepository.deleteByDprId before publishing the event")
    void deleteCascades() {
        DailyProgressReport dpr = baseDpr();
        when(dprRepository.findById(dprId)).thenReturn(Optional.of(dpr));
        when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(dprId)).thenReturn(List.of());

        service.delete(projectId, dprId);

        verify(issueRepository).deleteByDprId(dprId);
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private UpdateDailyProgressReportRequest request(List<DprIssueRow> issues) {
        return new UpdateDailyProgressReportRequest(
                LocalDate.of(2026, 5, 1), supervisorId, "Mohd Ismaila",
                null, null, activityId, "Bench Cutting", null, null, null, "Cum",
                new BigDecimal("80.0"), null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                issues);
    }

    private DailyProgressReport baseDpr() {
        DailyProgressReport d = DailyProgressReport.builder()
                .projectId(projectId)
                .reportDate(LocalDate.of(2026, 5, 1))
                .supervisorUserId(supervisorId)
                .supervisorName("Mohd Ismaila")
                .activityId(activityId)
                .activityName("Bench Cutting")
                .unit("Cum")
                .qtyExecuted(new BigDecimal("80"))
                .build();
        d.setId(dprId);
        return d;
    }

    private DprIssue baseIssue(UUID id, Instant openedAt, IssueStatus status) {
        DprIssue i = DprIssue.builder()
                .dprId(dprId)
                .projectId(projectId)
                .activityId(activityId)
                .activityName("Bench Cutting")
                .supervisorResourceId(supervisorId)
                .supervisorName("Mohd Ismaila")
                .assignedToResourceId(supervisorId)
                .assignedToName("Mohd Ismaila")
                .reportDate(LocalDate.of(2026, 5, 1))
                .category(IssueCategory.MATERIAL_SHORTAGE)
                .severity(IssueSeverity.HIGH)
                .status(status)
                .title("Material shortage")
                .openedAt(openedAt)
                .build();
        i.setId(id);
        return i;
    }
}
