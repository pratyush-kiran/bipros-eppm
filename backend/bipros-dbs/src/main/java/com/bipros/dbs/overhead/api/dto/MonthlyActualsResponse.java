package com.bipros.dbs.overhead.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row per plan item joined with its monthly actual (if any) for the given
 * {@code yearMonth}. Drives the actuals grid on the General Expenses page.
 */
public record MonthlyActualsResponse(
    Integer yearMonth,
    BigDecimal monthlyTotal,
    List<Row> rows
) {
    public record Row(
        GeneralExpensePlanItemDto planItem,
        GeneralExpenseMonthlyEntryDto actual
    ) {}
}
