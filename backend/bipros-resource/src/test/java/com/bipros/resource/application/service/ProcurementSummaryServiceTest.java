package com.bipros.resource.application.service;

import com.bipros.resource.application.dto.ProjectSubContractorSummaryResponse;
import com.bipros.resource.application.dto.ProjectVendorSummaryResponse;
import com.bipros.resource.application.dto.VendorMaterialLine;
import com.bipros.resource.application.dto.VendorReceiptLine;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.GoodsReceiptNote;
import com.bipros.resource.domain.model.Material;
import com.bipros.resource.domain.model.MaterialCategory;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.GoodsReceiptNoteRepository;
import com.bipros.resource.domain.repository.MaterialRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcurementSummaryServiceTest {

  @Mock private ActivitySubContractorAssignmentRepository assignmentRepository;
  @Mock private SubContractorMasterRepository subContractorMasterRepository;
  @Mock private GoodsReceiptNoteRepository grnRepository;
  @Mock private MaterialRepository materialRepository;

  private ProcurementSummaryService service;

  @BeforeEach
  void setUp() {
    service = new ProcurementSummaryService(
        assignmentRepository, subContractorMasterRepository, grnRepository, materialRepository);
  }

  // ---- sub-contractor cases ----

  @Test
  void emptyProjectReturnsEmptyList() {
    UUID projectId = UUID.randomUUID();
    when(assignmentRepository.findByProjectId(projectId)).thenReturn(List.of());

    assertThat(service.subContractorSummary(projectId)).isEmpty();
  }

  @Test
  void groupsAssignmentsBySubContractorAndSumsCost() {
    UUID projectId = UUID.randomUUID();
    UUID masterA = UUID.randomUUID();
    UUID masterB = UUID.randomUUID();

    ActivitySubContractorAssignment a1 = assignment(projectId, masterA, UUID.randomUUID(),
        "Formwork", "SQM", "100", "50", "5000", "40", "2000");
    ActivitySubContractorAssignment a2 = assignment(projectId, masterA, UUID.randomUUID(),
        "Rebar", "MT", "10", "800", "8000", "5", "4000");
    ActivitySubContractorAssignment b1 = assignment(projectId, masterB, UUID.randomUUID(),
        "Painting", "SQM", "200", "20", "4000", "0", "0");

    when(assignmentRepository.findByProjectId(projectId)).thenReturn(List.of(a1, a2, b1));
    when(subContractorMasterRepository.findAllById(any()))
        .thenReturn(List.of(master(masterA, "SC-001", "Al Noor"),
                            master(masterB, "SC-002", "Gulf Build")));

    List<ProjectSubContractorSummaryResponse> result = service.subContractorSummary(projectId);

    assertThat(result).hasSize(2);

    ProjectSubContractorSummaryResponse summaryA = result.stream()
        .filter(r -> r.subContractorMasterId().equals(masterA)).findFirst().orElseThrow();
    assertThat(summaryA.code()).isEqualTo("SC-001");
    assertThat(summaryA.name()).isEqualTo("Al Noor");
    assertThat(summaryA.location()).isEqualTo("Muscat");
    assertThat(summaryA.assignmentCount()).isEqualTo(2);
    assertThat(summaryA.plannedCost()).isEqualByComparingTo("13000");   // 5000 + 8000
    assertThat(summaryA.actualCost()).isEqualByComparingTo("6000");     // 2000 + 4000
    assertThat(summaryA.costVariance()).isEqualByComparingTo("7000");   // planned - actual (positive = under)
    assertThat(summaryA.lines()).hasSize(2);

    ProjectSubContractorSummaryResponse summaryB = result.stream()
        .filter(r -> r.subContractorMasterId().equals(masterB)).findFirst().orElseThrow();
    assertThat(summaryB.assignmentCount()).isEqualTo(1);
    assertThat(summaryB.plannedCost()).isEqualByComparingTo("4000");
    assertThat(summaryB.actualCost()).isEqualByComparingTo("0");
  }

  @Test
  void percentCompleteIsActualOverPlannedTimes100() {
    UUID projectId = UUID.randomUUID();
    UUID masterA = UUID.randomUUID();
    ActivitySubContractorAssignment a = assignment(projectId, masterA, UUID.randomUUID(),
        "Formwork", "SQM", "100", "50", "8000", "40", "2000");
    when(assignmentRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    when(subContractorMasterRepository.findAllById(any()))
        .thenReturn(List.of(master(masterA, "SC-001", "Al Noor")));

    List<ProjectSubContractorSummaryResponse> result = service.subContractorSummary(projectId);

    // 2000 / 8000 * 100 = 25.00
    assertThat(result.get(0).percentComplete()).isEqualByComparingTo("25.00");
  }

  @Test
  void percentCompleteIsZeroWhenPlannedCostZero() {
    UUID projectId = UUID.randomUUID();
    UUID masterA = UUID.randomUUID();
    ActivitySubContractorAssignment a = assignment(projectId, masterA, UUID.randomUUID(),
        "Mobilisation", "LS", "0", "0", "0", "0", "0");
    when(assignmentRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    when(subContractorMasterRepository.findAllById(any()))
        .thenReturn(List.of(master(masterA, "SC-001", "Al Noor")));

    List<ProjectSubContractorSummaryResponse> result = service.subContractorSummary(projectId);

    assertThat(result.get(0).percentComplete()).isEqualByComparingTo("0");
    assertThat(result.get(0).costVariance()).isEqualByComparingTo("0");
  }

  // ---- vendor cases ----

  @Test
  void vendorEmptyProjectReturnsEmptyList() {
    UUID projectId = UUID.randomUUID();
    when(grnRepository.findByProjectIdOrderByReceivedDateDesc(projectId)).thenReturn(List.of());
    when(materialRepository.findByProjectId(projectId)).thenReturn(List.of());

    assertThat(service.vendorSummary(projectId)).isEmpty();
  }

  @Test
  void groupsBySupplierSumsValueLastDateAndResolvesMaterialName() {
    UUID projectId = UUID.randomUUID();
    UUID supplierX = UUID.randomUUID();
    UUID supplierY = UUID.randomUUID();
    UUID cementId = UUID.randomUUID();
    UUID steelId = UUID.randomUUID();

    Material cement = material(cementId, projectId, supplierX,
        "MAT-001", "OPC Cement", MaterialCategory.CEMENT, "MT");
    Material steel = material(steelId, projectId, supplierY,
        "MAT-002", "Rebar Fe500", MaterialCategory.STEEL, "MT");

    GoodsReceiptNote g1 = grn(projectId, supplierX, cementId,
        "GRN-202606-0001", LocalDate.of(2026, 6, 10), "100", "50", "5000", "100", "0");
    GoodsReceiptNote g2 = grn(projectId, supplierX, cementId,
        "GRN-202606-0002", LocalDate.of(2026, 6, 20), "40", "50", "2000", "38", "2");
    GoodsReceiptNote g3 = grn(projectId, supplierY, steelId,
        "GRN-202606-0003", LocalDate.of(2026, 6, 15), "10", "800", "8000", "10", "0");

    // repository contract: already sorted by receivedDate desc
    when(grnRepository.findByProjectIdOrderByReceivedDateDesc(projectId))
        .thenReturn(List.of(g2, g3, g1));
    when(materialRepository.findByProjectId(projectId)).thenReturn(List.of(cement, steel));

    List<ProjectVendorSummaryResponse> result = service.vendorSummary(projectId);

    assertThat(result).hasSize(2);

    ProjectVendorSummaryResponse x = result.stream()
        .filter(v -> supplierX.equals(v.supplierOrganisationId())).findFirst().orElseThrow();
    assertThat(x.receiptCount()).isEqualTo(2);
    assertThat(x.totalValueReceived()).isEqualByComparingTo("7000");   // 5000 + 2000
    assertThat(x.lastReceiptDate()).isEqualTo(LocalDate.of(2026, 6, 20));
    assertThat(x.materialCount()).isEqualTo(1);
    assertThat(x.materials()).extracting(VendorMaterialLine::name).containsExactly("OPC Cement");
    assertThat(x.receipts()).extracting(VendorReceiptLine::materialName).containsOnly("OPC Cement");

    ProjectVendorSummaryResponse y = result.stream()
        .filter(v -> supplierY.equals(v.supplierOrganisationId())).findFirst().orElseThrow();
    assertThat(y.receiptCount()).isEqualTo(1);
    assertThat(y.totalValueReceived()).isEqualByComparingTo("8000");
    assertThat(y.lastReceiptDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    assertThat(y.receipts().get(0).materialName()).isEqualTo("Rebar Fe500");
  }

  @Test
  void nullSupplierGroupsUnderUnassignedBucket() {
    UUID projectId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();

    Material cement = material(materialId, projectId, null,
        "MAT-001", "OPC Cement", MaterialCategory.CEMENT, "MT");
    GoodsReceiptNote g = grn(projectId, null, materialId,
        "GRN-202606-0001", LocalDate.of(2026, 6, 10), "100", "50", "5000", "100", "0");

    when(grnRepository.findByProjectIdOrderByReceivedDateDesc(projectId)).thenReturn(List.of(g));
    when(materialRepository.findByProjectId(projectId)).thenReturn(List.of(cement));

    List<ProjectVendorSummaryResponse> result = service.vendorSummary(projectId);

    assertThat(result).hasSize(1);
    ProjectVendorSummaryResponse bucket = result.get(0);
    assertThat(bucket.supplierOrganisationId()).isNull();
    assertThat(bucket.receiptCount()).isEqualTo(1);
    assertThat(bucket.totalValueReceived()).isEqualByComparingTo("5000");
    assertThat(bucket.receipts().get(0).materialName()).isEqualTo("OPC Cement");
    assertThat(bucket.materials()).hasSize(1);
  }

  // ---- helpers ----

  private ActivitySubContractorAssignment assignment(
      UUID projectId, UUID masterId, UUID activityId,
      String workTypeName, String unit,
      String plannedUnits, String ratePerUnit, String plannedCost,
      String actualUnits, String actualCost) {
    return ActivitySubContractorAssignment.builder()
        .projectId(projectId)
        .subContractorMasterId(masterId)
        .activityId(activityId)
        .scWorkTypeId(UUID.randomUUID())
        .workTypeName(workTypeName)
        .unit(unit)
        .plannedUnits(new BigDecimal(plannedUnits))
        .ratePerUnit(new BigDecimal(ratePerUnit))
        .plannedCost(new BigDecimal(plannedCost))
        .actualUnits(new BigDecimal(actualUnits))
        .actualCost(new BigDecimal(actualCost))
        .build();
  }

  private SubContractorMaster master(UUID id, String code, String name) {
    SubContractorMaster m = SubContractorMaster.builder()
        .code(code)
        .name(name)
        .location("Muscat")
        .primaryContactName("Ali")
        .primaryContactNumber("+968-1234")
        .active(true)
        .build();
    m.setId(id);
    return m;
  }

  private Material material(
      UUID id, UUID projectId, UUID supplierId,
      String code, String name, MaterialCategory category, String unit) {
    Material m = Material.builder()
        .projectId(projectId)
        .approvedSupplierId(supplierId)
        .code(code)
        .name(name)
        .category(category)
        .unit(unit)
        .build();
    m.setId(id);
    return m;
  }

  private GoodsReceiptNote grn(
      UUID projectId, UUID supplierId, UUID materialId,
      String grnNumber, LocalDate receivedDate,
      String quantity, String unitRate, String amount,
      String accepted, String rejected) {
    GoodsReceiptNote g = GoodsReceiptNote.builder()
        .projectId(projectId)
        .supplierOrganisationId(supplierId)
        .materialId(materialId)
        .grnNumber(grnNumber)
        .receivedDate(receivedDate)
        .quantity(new BigDecimal(quantity))
        .unitRate(new BigDecimal(unitRate))
        .amount(new BigDecimal(amount))
        .acceptedQuantity(new BigDecimal(accepted))
        .rejectedQuantity(new BigDecimal(rejected))
        .build();
    g.setId(UUID.randomUUID());
    return g;
  }
}
