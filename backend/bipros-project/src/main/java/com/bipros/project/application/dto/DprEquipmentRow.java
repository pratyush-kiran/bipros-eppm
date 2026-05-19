package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.EquipmentAvailability;
import com.bipros.project.domain.model.EquipmentOwnership;
import com.bipros.project.domain.model.Shift;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record DprEquipmentRow(
    UUID id,
    UUID resourceAssignmentId,
    UUID resourceId,
    @NotBlank String equipmentType,
    String fleetNo,
    EquipmentOwnership ownership,
    Shift shift,
    @PositiveOrZero Integer nos,
    @PositiveOrZero BigDecimal workingHours,
    @PositiveOrZero BigDecimal idleHours,
    @PositiveOrZero BigDecimal breakdownHours,
    @PositiveOrZero BigDecimal fuelLitres,
    BigDecimal unitRate,
    String unitRateBasis,
    BigDecimal lineCost,
    String operatorName,
    EquipmentAvailability availabilityStatus,
    String remarks,
    UUID equipmentRoleVariantId,
    UUID roleId
) {
    public static DprEquipmentRow from(DprEquipment e) {
        return new DprEquipmentRow(
            e.getId(),
            e.getResourceAssignmentId(),
            e.getResourceId(),
            e.getEquipmentType(),
            e.getFleetNo(),
            e.getOwnership(),
            e.getShift(),
            e.getNos(),
            e.getWorkingHours(),
            e.getIdleHours(),
            e.getBreakdownHours(),
            e.getFuelLitres(),
            e.getUnitRate(),
            e.getUnitRateBasis(),
            e.getLineCost(),
            e.getOperatorName(),
            e.getAvailabilityStatus(),
            e.getRemarks(),
            e.getEquipmentRoleVariantId(),
            e.getRoleId());
    }

    public DprEquipment toEntity(UUID dprId) {
        return DprEquipment.builder()
            .dprId(dprId)
            .resourceAssignmentId(resourceAssignmentId)
            .resourceId(resourceId)
            .equipmentType(equipmentType)
            .fleetNo(fleetNo)
            .ownership(ownership)
            .shift(shift == null ? Shift.DAY : shift)
            .nos(nos)
            .workingHours(workingHours)
            .idleHours(idleHours)
            .breakdownHours(breakdownHours)
            .fuelLitres(fuelLitres)
            .unitRate(unitRate)
            .unitRateBasis(unitRateBasis)
            .lineCost(lineCost)
            .operatorName(operatorName)
            .availabilityStatus(availabilityStatus)
            .remarks(remarks)
            .equipmentRoleVariantId(equipmentRoleVariantId)
            .roleId(roleId)
            .build();
    }
}
