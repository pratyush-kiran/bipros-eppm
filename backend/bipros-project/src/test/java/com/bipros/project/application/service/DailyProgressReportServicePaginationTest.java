package com.bipros.project.application.service;

import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.DprPage;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprAttachmentRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.project.domain.repository.DprVoiceNoteRepository;
import com.bipros.project.domain.repository.BoqItemRepository;
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
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — day-cursor pagination + slim aggregates")
class DailyProgressReportServicePaginationTest {

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

  private DailyProgressReportService service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID dprA = UUID.randomUUID();
  private final UUID dprB = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, voiceNoteRepository, issueRepository,
        attachmentStorage, voiceNoteStorage, projectRepository, ledgerService, auditService,
        eventPublisher, null, boqItemRepository);
    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    lenient().when(manpowerRepository.sumNosByDprIdIn(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.sumNosByDprIdIn(any())).thenReturn(List.of());
    lenient().when(materialRepository.countByDprIdIn(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.countByDprIdIn(any())).thenReturn(List.of());
    lenient().when(issueRepository.findStatusSeverityByDprIdIn(any())).thenReturn(List.of());
  }

  private DailyProgressReport dpr(UUID id, LocalDate date, String activity, BigDecimal qty) {
    DailyProgressReport d = new DailyProgressReport();
    d.setId(id);
    d.setProjectId(projectId);
    d.setReportDate(date);
    d.setActivityName(activity);
    d.setUnit("Cum");
    d.setQtyExecuted(qty);
    d.setApprovalStatus(DprApprovalStatus.SUBMITTED);
    d.setSupervisorName("Ravi");
    return d;
  }

  @Test
  @DisplayName("first page: returns slim rows for the requested days and reports hasMore when extra days exist")
  void firstPage_hasMore() {
    LocalDate d1 = LocalDate.of(2026, 3, 10);
    LocalDate d2 = LocalDate.of(2026, 3, 9);
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1, d2, LocalDate.of(2026, 3, 8)));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1, d2)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("100")),
                            dpr(dprB, d2, "Earthworks", new BigDecimal("50"))));

    DprPage page = service.listPaged(projectId, null, null, null, null, 2);

    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isEqualTo(d2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).reportDate()).isEqualTo(d1);
    assertThat(page.items().get(0).qtyExecuted()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("last page: fewer days than requested ⇒ hasMore false, nextCursor null")
  void lastPage_noMore() {
    LocalDate d1 = LocalDate.of(2026, 3, 1);
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("10"))));

    DprPage page = service.listPaged(projectId, null, null, null, null, 14);

    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.items()).hasSize(1);
  }

  @Test
  @DisplayName("empty: no dates ⇒ empty page")
  void empty() {
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of());

    DprPage page = service.listPaged(projectId, null, null, null, null, 14);

    assertThat(page.items()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("aggregates: nos sums, counts, and issue live/open/critical flags map onto the right dpr")
  void aggregates() {
    LocalDate d1 = LocalDate.of(2026, 3, 10);
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("100"))));
    when(manpowerRepository.sumNosByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 12L}));
    when(equipmentRepository.sumNosByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 3L}));
    when(materialRepository.countByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 2L}));
    when(attachmentRepository.countByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 4L}));
    when(issueRepository.findStatusSeverityByDprIdIn(any())).thenReturn(List.<Object[]>of(
        new Object[]{dprA, IssueStatus.OPEN, IssueSeverity.CRITICAL},
        new Object[]{dprA, IssueStatus.RESOLVED, IssueSeverity.LOW},
        new Object[]{dprA, IssueStatus.CANCELLED, IssueSeverity.HIGH}));

    var row = service.listPaged(projectId, null, null, null, null, 14).items().get(0);

    assertThat(row.manpowerNos()).isEqualTo(12);
    assertThat(row.equipmentNos()).isEqualTo(3);
    assertThat(row.materialCount()).isEqualTo(2);
    assertThat(row.photoCount()).isEqualTo(4);
    assertThat(row.issueCount()).isEqualTo(2);
    assertThat(row.openIssueCount()).isEqualTo(1);
    assertThat(row.hasCriticalOpen()).isTrue();
  }
}
