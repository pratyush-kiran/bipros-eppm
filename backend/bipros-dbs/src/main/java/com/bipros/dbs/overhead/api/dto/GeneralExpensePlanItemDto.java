package com.bipros.dbs.overhead.api.dto;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseFormulaType;
import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseUnit;

import java.math.BigDecimal;
import java.util.UUID;

public record GeneralExpensePlanItemDto(
    UUID id,
    UUID projectId,
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
    public static GeneralExpensePlanItemDto from(GeneralExpensePlanItem e) {
        return new GeneralExpensePlanItemDto(
            e.getId(), e.getProjectId(), e.getDescription(), e.getUnit(),
            e.getRate(), e.getPlanQty(), e.getPlanAmount(),
            e.getFormulaType(), e.getFormulaPct(),
            e.getSortOrder(), e.getActive()
        );
    }
}
