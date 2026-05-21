package com.bipros.dbs.overhead.api.dto;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;

import java.math.BigDecimal;

public record MonthlyEntryUpsertRequest(
    BigDecimal achievedQty,
    BigDecimal achievedAmount,
    String notes
) {
    public GeneralExpenseMonthlyEntry toEntity() {
        return GeneralExpenseMonthlyEntry.builder()
            .achievedQty(achievedQty)
            .achievedAmount(achievedAmount)
            .notes(notes)
            .build();
    }
}
