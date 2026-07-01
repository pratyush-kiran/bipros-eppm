package com.bipros.resource.application.service;

import com.bipros.resource.application.dto.ProjectSubContractorSummaryResponse;
import com.bipros.resource.application.dto.ProjectVendorSummaryResponse;
import com.bipros.resource.application.dto.SubContractorAssignmentLine;
import com.bipros.resource.application.dto.VendorMaterialLine;
import com.bipros.resource.application.dto.VendorReceiptLine;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.GoodsReceiptNote;
import com.bipros.resource.domain.model.Material;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.GoodsReceiptNoteRepository;
import com.bipros.resource.domain.repository.MaterialRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProcurementSummaryService {

  private final ActivitySubContractorAssignmentRepository assignmentRepository;
  private final SubContractorMasterRepository subContractorMasterRepository;
  private final GoodsReceiptNoteRepository grnRepository;
  private final MaterialRepository materialRepository;

  public List<ProjectSubContractorSummaryResponse> subContractorSummary(UUID projectId) {
    List<ActivitySubContractorAssignment> assignments =
        assignmentRepository.findByProjectId(projectId);
    if (assignments.isEmpty()) {
      return List.of();
    }

    Map<UUID, List<ActivitySubContractorAssignment>> byMaster = assignments.stream()
        .collect(Collectors.groupingBy(ActivitySubContractorAssignment::getSubContractorMasterId));

    Map<UUID, SubContractorMaster> masters = subContractorMasterRepository
        .findAllById(byMaster.keySet()).stream()
        .collect(Collectors.toMap(SubContractorMaster::getId, m -> m));

    List<ProjectSubContractorSummaryResponse> result = new ArrayList<>();
    for (Map.Entry<UUID, List<ActivitySubContractorAssignment>> entry : byMaster.entrySet()) {
      UUID masterId = entry.getKey();
      List<ActivitySubContractorAssignment> group = entry.getValue();
      SubContractorMaster master = masters.get(masterId);

      BigDecimal plannedCost = group.stream()
          .map(a -> nz(a.getPlannedCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal actualCost = group.stream()
          .map(a -> nz(a.getActualCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal costVariance = plannedCost.subtract(actualCost);
      BigDecimal percentComplete = percentComplete(plannedCost, actualCost);

      List<SubContractorAssignmentLine> lines = group.stream()
          .map(a -> new SubContractorAssignmentLine(
              a.getActivityId(), a.getWorkTypeName(), a.getUnit(),
              a.getPlannedUnits(), a.getRatePerUnit(), a.getPlannedCost(),
              a.getActualUnits(), a.getActualCost()))
          .toList();

      result.add(new ProjectSubContractorSummaryResponse(
          masterId,
          master != null ? master.getCode() : null,
          master != null ? master.getName() : null,
          master != null ? master.getLocation() : null,
          master != null ? master.getPrimaryContactName() : null,
          master != null ? master.getPrimaryContactNumber() : null,
          group.size(),
          plannedCost, actualCost, costVariance, percentComplete,
          lines));
    }
    return result;
  }

  public List<ProjectVendorSummaryResponse> vendorSummary(UUID projectId) {
    List<GoodsReceiptNote> grns = grnRepository.findByProjectIdOrderByReceivedDateDesc(projectId);
    List<Material> materials = materialRepository.findByProjectId(projectId);
    if (grns.isEmpty() && materials.isEmpty()) {
      return List.of();
    }

    Map<UUID, Material> materialById = materials.stream()
        .collect(Collectors.toMap(Material::getId, m -> m));

    // Group GRNs by supplier; null supplier -> "Unassigned vendor" bucket (never dropped).
    Map<UUID, List<GoodsReceiptNote>> grnsBySupplier = new LinkedHashMap<>();
    List<GoodsReceiptNote> unassignedGrns = new ArrayList<>();
    for (GoodsReceiptNote grn : grns) {
      UUID supplierId = grn.getSupplierOrganisationId();
      if (supplierId == null) {
        unassignedGrns.add(grn);
      } else {
        grnsBySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(grn);
      }
    }

    // Group materials by approved supplier; null supplier -> same "Unassigned" bucket.
    Map<UUID, List<Material>> materialsBySupplier = new LinkedHashMap<>();
    List<Material> unassignedMaterials = new ArrayList<>();
    for (Material material : materials) {
      UUID supplierId = material.getApprovedSupplierId();
      if (supplierId == null) {
        unassignedMaterials.add(material);
      } else {
        materialsBySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(material);
      }
    }

    Set<UUID> supplierIds = new LinkedHashSet<>();
    supplierIds.addAll(grnsBySupplier.keySet());
    supplierIds.addAll(materialsBySupplier.keySet());

    List<ProjectVendorSummaryResponse> result = new ArrayList<>();
    for (UUID supplierId : supplierIds) {
      result.add(buildVendorSummary(
          supplierId,
          grnsBySupplier.getOrDefault(supplierId, List.of()),
          materialsBySupplier.getOrDefault(supplierId, List.of()),
          materialById));
    }
    if (!unassignedGrns.isEmpty() || !unassignedMaterials.isEmpty()) {
      result.add(buildVendorSummary(null, unassignedGrns, unassignedMaterials, materialById));
    }
    return result;
  }

  private ProjectVendorSummaryResponse buildVendorSummary(
      UUID supplierId,
      List<GoodsReceiptNote> grns,
      List<Material> materials,
      Map<UUID, Material> materialById) {

    BigDecimal totalValueReceived = grns.stream()
        .map(g -> nz(g.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

    LocalDate lastReceiptDate = grns.stream()
        .map(GoodsReceiptNote::getReceivedDate)
        .filter(Objects::nonNull)
        .max(LocalDate::compareTo)
        .orElse(null);

    List<VendorReceiptLine> receipts = grns.stream()
        .map(g -> {
          Material m = materialById.get(g.getMaterialId());
          return new VendorReceiptLine(
              g.getId(), g.getGrnNumber(), g.getReceivedDate(), g.getMaterialId(),
              m != null ? m.getName() : null,
              m != null ? m.getUnit() : null,
              g.getQuantity(), g.getUnitRate(), g.getAmount(),
              g.getAcceptedQuantity(), g.getRejectedQuantity());
        })
        .toList();

    List<VendorMaterialLine> materialLines = materials.stream()
        .map(m -> new VendorMaterialLine(
            m.getId(), m.getCode(), m.getName(),
            m.getCategory() != null ? m.getCategory().name() : null,
            m.getUnit()))
        .toList();

    return new ProjectVendorSummaryResponse(
        supplierId,
        materialLines.size(),
        receipts.size(),
        totalValueReceived,
        lastReceiptDate,
        receipts,
        materialLines);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal percentComplete(BigDecimal plannedCost, BigDecimal actualCost) {
    if (plannedCost == null || plannedCost.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return actualCost.multiply(BigDecimal.valueOf(100))
        .divide(plannedCost, 2, RoundingMode.HALF_UP);
  }
}
