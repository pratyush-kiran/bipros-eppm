package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprSubContractor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record DprSubContractorRow(
    UUID id,

    @NotNull(message = "activitySubContractorAssignmentId is required")
    UUID activitySubContractorAssignmentId,

    // Snapshots — response-only, set by the service from the assignment + master.
    UUID subContractorMasterId,
    String subContractorName,
    String subContractorCode,
    String workActivityName,
    String unit,
    BigDecimal ratePerUnit,

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.0001", inclusive = true, message = "quantity must be > 0")
    BigDecimal quantity,

    /** Computed = quantity × ratePerUnit. Response-only. */
    BigDecimal lineCost,

    @Size(max = 500, message = "remarks must be at most 500 characters")
    String remarks
) {
  public static DprSubContractorRow from(DprSubContractor e) {
    return new DprSubContractorRow(
        e.getId(),
        e.getActivitySubContractorAssignmentId(),
        e.getSubContractorMasterId(),
        e.getSubContractorName(),
        e.getSubContractorCode(),
        null,         // workActivityName — set by service from assignment
        null,         // unit — set by service
        null,         // ratePerUnit — set by service
        e.getQuantity(),
        null,         // lineCost — set by service
        e.getRemarks());
  }

  /** Enriched factory used by the service to attach assignment snapshots to the response. */
  public static DprSubContractorRow withAssignmentSnapshot(
      DprSubContractor e, String workActivityName, String unit, BigDecimal ratePerUnit) {
    BigDecimal qty = e.getQuantity();
    BigDecimal lineCost = (qty == null || ratePerUnit == null)
        ? null : qty.multiply(ratePerUnit);
    return new DprSubContractorRow(
        e.getId(),
        e.getActivitySubContractorAssignmentId(),
        e.getSubContractorMasterId(),
        e.getSubContractorName(),
        e.getSubContractorCode(),
        workActivityName,
        unit,
        ratePerUnit,
        qty,
        lineCost,
        e.getRemarks());
  }

  public DprSubContractor toEntity(UUID dprId) {
    return DprSubContractor.builder()
        .dprId(dprId)
        .activitySubContractorAssignmentId(activitySubContractorAssignmentId)
        .subContractorMasterId(subContractorMasterId)
        .subContractorName(subContractorName)
        .subContractorCode(subContractorCode)
        .quantity(quantity)
        .remarks(remarks)
        .build();
  }
}
