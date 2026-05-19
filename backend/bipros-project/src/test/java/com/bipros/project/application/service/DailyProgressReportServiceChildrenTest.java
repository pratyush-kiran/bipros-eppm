package com.bipros.project.application.service;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DailyProgressReportResponse;
import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.DprMaterialRow;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.model.EquipmentAvailability;
import com.bipros.project.domain.model.EquipmentOwnership;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Multi-row child collection lifecycle for DailyProgressReportService — the highest-risk new
 * behavior in this PR. Verifies that:
 * <ul>
 *   <li>create() persists each child collection with the parent's dprId.</li>
 *   <li>update() does full-replacement (delete-by-dprId then re-insert).</li>
 *   <li>delete() cascades to all three child tables.</li>
 *   <li>The published {@link DprSubmittedEvent} carries correct child counts and totals.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — child collection lifecycle")
class DailyProgressReportServiceChildrenTest {

  @Mock private DailyProgressReportRepository dprRepository;
  @Mock private DprManpowerRepository manpowerRepository;
  @Mock private DprEquipmentRepository equipmentRepository;
  @Mock private DprMaterialRepository materialRepository;
  @Mock private com.bipros.project.domain.repository.DprAttachmentRepository attachmentRepository;
  @Mock private com.bipros.project.domain.repository.DprIssueRepository issueRepository;
  @Mock private com.bipros.project.infrastructure.storage.DprAttachmentStorageService attachmentStorage;
  @Mock private ProjectRepository projectRepository;
  @Mock private DailyActivityResourceOutputService ledgerService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private com.bipros.project.domain.repository.BoqItemRepository boqItemRepository;

  private DailyProgressReportService service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID dprId = UUID.randomUUID();
  private final UUID activityId = UUID.randomUUID();
  private final UUID assignmentMpId = UUID.randomUUID();
  private final UUID assignmentEqId = UUID.randomUUID();
  private final UUID assignmentMatId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        attachmentRepository, issueRepository, attachmentStorage,
        projectRepository, ledgerService, auditService, eventPublisher, null, boqItemRepository);
    lenient().when(attachmentRepository.findByDprIdOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of());
    lenient().when(attachmentRepository.findByDprIdIn(any())).thenReturn(java.util.List.of());
    lenient().when(issueRepository.findByDprIdOrderByOpenedAtAsc(any())).thenReturn(java.util.List.of());
    lenient().when(issueRepository.findByDprIdIn(any())).thenReturn(java.util.List.of());
    lenient().when(issueRepository.saveAll(any())).thenAnswer(this::echoEntities);
    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    lenient().when(dprRepository.sumQtyExecutedThroughDate(any(), any(), any()))
        .thenReturn(BigDecimal.ZERO);
    lenient().when(manpowerRepository.saveAll(any())).thenAnswer(this::echoEntities);
    lenient().when(equipmentRepository.saveAll(any())).thenAnswer(this::echoEntities);
    lenient().when(materialRepository.saveAll(any())).thenAnswer(this::echoEntities);
    lenient().when(dprRepository.save(any())).thenAnswer(inv -> {
      DailyProgressReport d = inv.getArgument(0);
      if (d.getId() == null) d.setId(dprId);
      return d;
    });
  }

  @Test
  @DisplayName("create persists 3 manpower + 2 equipment + 1 material rows and emits enriched event")
  void createWithChildren() {
    CreateDailyProgressReportRequest req = new CreateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 1), null, "Mohd Ismaila",
        4300L, 4500L, activityId, "Bench Cutting", null, null, null, "Cum",
        new BigDecimal("80.0"), "Clear", null,
        null, null, null, null, null, null, null, null, null, null,
        List.of(
            mp("Operator", ManpowerCategory.SKILLED, 1, 11.0, 0.0),
            mp("Helper", ManpowerCategory.UNSKILLED, 2, 11.0, 1.5),
            mp("Foreman", ManpowerCategory.SKILLED, 1, 8.0, 0.0)),
        List.of(
            equipRow("Excavator", "Exc-45", 1, 10.0, 30.0),
            equipRow("Tipper", "Tipper-104", 2, 9.0, 50.0)),
        List.of(matRow("Aggregate 20mm", "Cum", 12.0)),
        null);

    DailyProgressReportResponse resp = service.create(projectId, req);

    assertThat(resp.manpower()).hasSize(3);
    assertThat(resp.equipment()).hasSize(2);
    assertThat(resp.materials()).hasSize(1);

    // Each child collection saved with parent's dprId.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DprManpower>> mpCap = ArgumentCaptor.forClass(List.class);
    verify(manpowerRepository).saveAll(mpCap.capture());
    assertThat(mpCap.getValue()).hasSize(3);
    assertThat(mpCap.getValue()).allMatch(m -> dprId.equals(m.getDprId()));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DprEquipment>> eqCap = ArgumentCaptor.forClass(List.class);
    verify(equipmentRepository).saveAll(eqCap.capture());
    assertThat(eqCap.getValue()).hasSize(2);
    assertThat(eqCap.getValue()).allMatch(e -> dprId.equals(e.getDprId()));

    // Event carries totals: manpower hours = (11+0) + (11+1.5) + (8+0) = 31.5; equipment hrs = 19; fuel = 80.
    ArgumentCaptor<DprSubmittedEvent> evCap = ArgumentCaptor.forClass(DprSubmittedEvent.class);
    verify(eventPublisher).publishEvent(evCap.capture());
    DprSubmittedEvent ev = evCap.getValue();
    assertThat(ev.eventType()).isEqualTo(DprMutationType.CREATED);
    assertThat(ev.manpowerCount()).isEqualTo(3);
    assertThat(ev.equipmentCount()).isEqualTo(2);
    assertThat(ev.materialCount()).isEqualTo(1);
    assertThat(ev.totalManpowerHours()).isEqualByComparingTo(new BigDecimal("31.5"));
    assertThat(ev.totalEquipmentHours()).isEqualByComparingTo(new BigDecimal("19.0"));
    assertThat(ev.totalFuelLitres()).isEqualByComparingTo(new BigDecimal("80.0"));
  }

  @Test
  @DisplayName("update replaces children: deleteByDprId on all three tables, then saveAll new lists")
  void updateReplacesChildren() {
    DailyProgressReport existing = baseDpr();
    when(dprRepository.findById(dprId)).thenReturn(Optional.of(existing));

    UpdateDailyProgressReportRequest req = new UpdateDailyProgressReportRequest(
        LocalDate.of(2026, 5, 2), null, "Mohd Ismaila",
        4300L, 4500L, activityId, "Bench Cutting", null, null, null, "Cum",
        new BigDecimal("90.0"), null, null,
        null, null, null, null, null, null, null, null, null, null,
        List.of(mp("Operator", ManpowerCategory.SKILLED, 1, 11.0, 0.0)),  // 3 → 1
        List.of(),  // 2 → 0
        List.of(matRow("Sand", "Cum", 5.0)),  // 1 → 1 (different)
        null);

    service.update(projectId, dprId, req);

    // Replace semantic: every child table gets a deleteByDprId call.
    verify(manpowerRepository).deleteByDprId(dprId);
    verify(equipmentRepository).deleteByDprId(dprId);
    verify(materialRepository).deleteByDprId(dprId);
    verify(manpowerRepository).flush();
    verify(equipmentRepository).flush();
    verify(materialRepository).flush();

    // saveAll for non-empty lists; empty list is a no-op (the helper short-circuits)
    verify(manpowerRepository, times(1)).saveAll(any());
    verify(equipmentRepository, times(0)).saveAll(any());
    verify(materialRepository, times(1)).saveAll(any());

    ArgumentCaptor<DprSubmittedEvent> evCap = ArgumentCaptor.forClass(DprSubmittedEvent.class);
    verify(eventPublisher).publishEvent(evCap.capture());
    DprSubmittedEvent ev = evCap.getValue();
    assertThat(ev.eventType()).isEqualTo(DprMutationType.UPDATED);
    assertThat(ev.manpowerCount()).isEqualTo(1);
    assertThat(ev.equipmentCount()).isEqualTo(0);
    assertThat(ev.materialCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("delete cascades: all three child tables get deleteByDprId before parent delete")
  void deleteCascades() {
    DailyProgressReport existing = baseDpr();
    when(dprRepository.findById(dprId)).thenReturn(Optional.of(existing));

    service.delete(projectId, dprId);

    verify(manpowerRepository).deleteByDprId(dprId);
    verify(equipmentRepository).deleteByDprId(dprId);
    verify(materialRepository).deleteByDprId(dprId);
    verify(dprRepository).delete(existing);

    // DELETE event uses withoutChildren (zeros for counts/totals) per the existing fact_dpr_logs convention.
    ArgumentCaptor<DprSubmittedEvent> evCap = ArgumentCaptor.forClass(DprSubmittedEvent.class);
    verify(eventPublisher).publishEvent(evCap.capture());
    DprSubmittedEvent ev = evCap.getValue();
    assertThat(ev.eventType()).isEqualTo(DprMutationType.DELETED);
    assertThat(ev.manpowerCount()).isZero();
  }

  @Test
  @DisplayName("list() batches one child query per table — no N+1 across DPR rows")
  void listBatchesChildren() {
    DailyProgressReport a = baseDpr();
    DailyProgressReport b = baseDpr();
    b.setId(UUID.randomUUID());
    LocalDate from = LocalDate.of(2026, 5, 1);
    LocalDate to = LocalDate.of(2026, 5, 5);
    when(dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
            eq(projectId), eq(from), eq(to)))
        .thenReturn(List.of(a, b));
    when(manpowerRepository.findByDprIdIn(any())).thenReturn(List.of());
    when(equipmentRepository.findByDprIdIn(any())).thenReturn(List.of());
    when(materialRepository.findByDprIdIn(any())).thenReturn(List.of());

    service.list(projectId, from, to, null);

    // Exactly one batch fetch per child table — never per-DPR.
    verify(manpowerRepository, times(1)).findByDprIdIn(any());
    verify(equipmentRepository, times(1)).findByDprIdIn(any());
    verify(materialRepository, times(1)).findByDprIdIn(any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private DailyProgressReport baseDpr() {
    DailyProgressReport d = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(LocalDate.of(2026, 5, 1))
        .supervisorName("Mohd Ismaila")
        .activityName("Bench Cutting")
        .unit("Cum")
        .qtyExecuted(new BigDecimal("80.0"))
        .build();
    d.setId(dprId);
    return d;
  }

  private DprManpowerRow mp(String trade, ManpowerCategory cat, int nos, double hours, double ot) {
    return new DprManpowerRow(
        null, assignmentMpId, null, trade, cat,
        com.bipros.project.domain.model.Shift.DAY, nos,
        BigDecimal.valueOf(hours), BigDecimal.valueOf(ot), null,
        null, null, null, null, null, null, null);
  }

  private DprEquipmentRow equipRow(String type, String fleet, int nos, double hours, double fuel) {
    return new DprEquipmentRow(
        null, assignmentEqId, null, type, fleet, EquipmentOwnership.OWNED,
        com.bipros.project.domain.model.Shift.DAY, nos,
        BigDecimal.valueOf(hours), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.valueOf(fuel), null, null, null, null, EquipmentAvailability.UTILIZED, null,
        null, null);
  }

  private DprMaterialRow matRow(String name, String unit, double qty) {
    return new DprMaterialRow(
        null, assignmentMatId, null, null, name, BigDecimal.valueOf(qty), unit,
        null, null, null, null, null, null, null, null);
  }

  /** saveAll(echo) — return the same entities after stamping a fake id on any null. */
  private Object echoEntities(org.mockito.invocation.InvocationOnMock inv) {
    @SuppressWarnings("unchecked")
    List<Object> in = (List<Object>) inv.getArgument(0);
    return in;
  }
}
