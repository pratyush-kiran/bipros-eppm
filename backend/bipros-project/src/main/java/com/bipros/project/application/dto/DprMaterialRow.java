package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprMaterial;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record DprMaterialRow(
    UUID id,
    UUID resourceAssignmentId,
    UUID materialId,
    UUID resourceId,
    @NotBlank String materialName,
    @PositiveOrZero BigDecimal quantity,
    String unit,
    String source,
    String batchNo,
    String vendorName,
    BigDecimal unitRate,
    BigDecimal lineCost,
    String remarks,
    UUID materialRoleVariantId,
    UUID roleId
) {
    public static DprMaterialRow from(DprMaterial e) {
        return new DprMaterialRow(
            e.getId(),
            e.getResourceAssignmentId(),
            e.getMaterialId(),
            e.getResourceId(),
            e.getMaterialName(),
            e.getQuantity(),
            e.getUnit(),
            e.getSource(),
            e.getBatchNo(),
            e.getVendorName(),
            e.getUnitRate(),
            e.getLineCost(),
            e.getRemarks(),
            e.getMaterialRoleVariantId(),
            e.getRoleId());
    }

    public DprMaterial toEntity(UUID dprId) {
        return DprMaterial.builder()
            .dprId(dprId)
            .resourceAssignmentId(resourceAssignmentId)
            .materialId(materialId)
            .resourceId(resourceId)
            .materialName(materialName)
            .quantity(quantity)
            .unit(unit)
            .source(source)
            .batchNo(batchNo)
            .vendorName(vendorName)
            .unitRate(unitRate)
            .lineCost(lineCost)
            .remarks(remarks)
            .materialRoleVariantId(materialRoleVariantId)
            .roleId(roleId)
            .build();
    }
}
