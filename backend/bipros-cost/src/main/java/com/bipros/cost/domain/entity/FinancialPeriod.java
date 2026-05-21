package com.bipros.cost.domain.entity;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_periods", schema = "cost",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_financial_period_project_sortorder",
                columnNames = {"project_id", "sort_order"}))

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinancialPeriod extends BaseEntity {

    /**
     * Owning project. Periods are project-scoped — each project owns its own set of quarters
     * derived from its planned start/finish dates by {@code FinancialPeriodAutoGenerator}. No
     * tenant-wide period sharing.
     */
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "period_type")
    private String periodType;

    @Column(name = "is_closed")
    private Boolean isClosed;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
