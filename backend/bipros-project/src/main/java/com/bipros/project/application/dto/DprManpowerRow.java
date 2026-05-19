package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.model.Shift;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Manpower line item under a DPR row. {@code id} is null on create / non-null on update so the
 * client can preserve audit fields if it wants to — but the service treats every save as a full
 * replacement (delete-by-dprId then re-insert), so {@code id} is informational only.
 */
public record DprManpowerRow(
    UUID id,
    UUID resourceAssignmentId,
    UUID resourceId,
    @NotBlank String trade,
    ManpowerCategory category,
    Shift shift,
    @PositiveOrZero Integer nos,
    @PositiveOrZero BigDecimal workingHours,
    @PositiveOrZero BigDecimal otHours,
    @PositiveOrZero BigDecimal idleHours,
    BigDecimal unitRate,
    String unitRateBasis,
    BigDecimal lineCost,
    String contractorName,
    String remarks,
    /** Role-only model: which {@code ManpowerRoleRate} variant was consumed. */
    UUID manpowerRoleRateId,
    UUID roleId
) {
    public static DprManpowerRow from(DprManpower e) {
        return new DprManpowerRow(
            e.getId(),
            e.getResourceAssignmentId(),
            e.getResourceId(),
            e.getTrade(),
            e.getCategory(),
            e.getShift(),
            e.getNos(),
            e.getWorkingHours(),
            e.getOtHours(),
            e.getIdleHours(),
            e.getUnitRate(),
            e.getUnitRateBasis(),
            e.getLineCost(),
            e.getContractorName(),
            e.getRemarks(),
            e.getManpowerRoleRateId(),
            e.getRoleId());
    }

    public DprManpower toEntity(UUID dprId) {
        return DprManpower.builder()
            .dprId(dprId)
            .resourceAssignmentId(resourceAssignmentId)
            .resourceId(resourceId)
            .trade(trade)
            .category(category)
            .shift(shift == null ? Shift.DAY : shift)
            .nos(nos)
            .workingHours(workingHours)
            .otHours(otHours)
            .idleHours(idleHours)
            .unitRate(unitRate)
            .unitRateBasis(unitRateBasis)
            .lineCost(lineCost)
            .contractorName(contractorName)
            .remarks(remarks)
            .manpowerRoleRateId(manpowerRoleRateId)
            .roleId(roleId)
            .build();
    }
}
