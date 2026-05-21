package com.bipros.dbs.overhead.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Monthly actual for a single Section G plan line. Logged once per month by the PM.
 * {@code yearMonth} is encoded as {@code year * 100 + month} (e.g. 202605 for May 2026)
 * so we can index it as a plain integer without a custom JPA converter.
 */
@Entity
@Table(
    name = "general_expense_monthly_entry",
    schema = "dbs",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_genexp_monthly_item_period", columnNames = {"plan_item_id", "year_month"})
    },
    indexes = {
        @Index(name = "idx_genexp_monthly_project_period", columnList = "project_id,year_month")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneralExpenseMonthlyEntry extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "plan_item_id", nullable = false)
    private UUID planItemId;

    /** {@code year * 100 + month} — e.g. 202605 for May 2026. */
    @Column(name = "year_month", nullable = false)
    private Integer yearMonth;

    @Column(name = "achieved_qty", precision = 19, scale = 2)
    private BigDecimal achievedQty;

    @Column(name = "achieved_amount", precision = 19, scale = 2)
    private BigDecimal achievedAmount;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "logged_by_user_id")
    private UUID loggedByUserId;
}
