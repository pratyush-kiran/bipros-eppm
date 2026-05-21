package com.bipros.dbs.overhead.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Section G "General Expenses" plan line. One row per project per line item
 * (Electricity, Water, Rent, …). PM edits {@code planQty}/{@code planAmount}
 * after the seeded 20 default rows land at project creation.
 */
@Entity
@Table(
    name = "general_expense_plan_item",
    schema = "dbs",
    indexes = {
        @Index(name = "idx_genexp_plan_project", columnList = "project_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneralExpensePlanItem extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 16)
    private GeneralExpenseUnit unit;

    @Column(name = "rate", precision = 19, scale = 2)
    private BigDecimal rate;

    @Column(name = "plan_qty", precision = 19, scale = 2)
    private BigDecimal planQty;

    @Column(name = "plan_amount", precision = 19, scale = 2)
    private BigDecimal planAmount;

    /** {@code NONE} for direct items; {@code PCT_CONTRACT_VALUE} for Insurance (0.015 %)
     *  and Bank Charges (0.01 %) — UI shows the formula hint, but {@code planAmount}
     *  remains the editable source of truth. */
    @Enumerated(EnumType.STRING)
    @Column(name = "formula_type", length = 32)
    private GeneralExpenseFormulaType formulaType;

    /** Stored as decimal fraction (0.00015 for 0.015 %). */
    @Column(name = "formula_pct", precision = 9, scale = 6)
    private BigDecimal formulaPct;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
