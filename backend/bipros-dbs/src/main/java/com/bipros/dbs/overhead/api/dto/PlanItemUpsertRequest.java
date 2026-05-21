package com.bipros.dbs.overhead.api.dto;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseFormulaType;
import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseUnit;

import java.math.BigDecimal;

public record PlanItemUpsertRequest(
    String description,
    GeneralExpenseUnit unit,
    BigDecimal rate,
    BigDecimal planQty,
    BigDecimal planAmount,
    GeneralExpenseFormulaType formulaType,
    BigDecimal formulaPct,
    Integer sortOrder,
    Boolean active
) {
    public GeneralExpensePlanItem toEntity() {
        return GeneralExpensePlanItem.builder()
            .description(description)
            .unit(unit)
            .rate(rate)
            .planQty(planQty)
            .planAmount(planAmount)
            .formulaType(formulaType)
            .formulaPct(formulaPct)
            .sortOrder(sortOrder)
            .active(active)
            .build();
    }
}
