package com.bipros.dbs.overhead.api.dto;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GeneralExpenseMonthlyEntryDto(
    UUID id,
    UUID projectId,
    UUID planItemId,
    Integer yearMonth,
    BigDecimal achievedQty,
    BigDecimal achievedAmount,
    String notes,
    UUID loggedByUserId,
    Instant updatedAt
) {
    public static GeneralExpenseMonthlyEntryDto from(GeneralExpenseMonthlyEntry e) {
        return new GeneralExpenseMonthlyEntryDto(
            e.getId(), e.getProjectId(), e.getPlanItemId(), e.getYearMonth(),
            e.getAchievedQty(), e.getAchievedAmount(), e.getNotes(),
            e.getLoggedByUserId(), e.getUpdatedAt()
        );
    }
}
