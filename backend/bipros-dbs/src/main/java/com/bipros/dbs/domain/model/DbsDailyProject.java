package com.bipros.dbs.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Project-level DBS rollup — SUM of every supervisor row for the project on that day.
 * {@code supervisorCount} / {@code dprCount} surface engagement metrics on the PM tab.
 */
@Entity
@Table(name = "dbs_daily_project", schema = "dbs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsDailyProject extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "engineer_ids", columnDefinition = "TEXT")
    private String engineerIds;

    @Column(name = "supervisor_count")
    private Integer supervisorCount;

    @Column(name = "dpr_count")
    private Integer dprCount;

    @Column(name = "material_amount", precision = 19, scale = 2)
    private BigDecimal materialAmount;

    @Column(name = "manpower_amount", precision = 19, scale = 2)
    private BigDecimal manpowerAmount;

    @Column(name = "admin_amount", precision = 19, scale = 2)
    private BigDecimal adminAmount;

    @Column(name = "machinery_amount", precision = 19, scale = 2)
    private BigDecimal machineryAmount;

    @Column(name = "fuel_amount", precision = 19, scale = 2)
    private BigDecimal fuelAmount;

    @Column(name = "subcontract_amount", precision = 19, scale = 2)
    private BigDecimal subcontractAmount;

    /**
     * Section G — General Expenses (monthly overheads), prorated to this day.
     * Computed as {@code monthlyTotal / yearMonth.lengthOfMonth()} so that the
     * day-view sum across the month equals the logged monthly total. PM tier
     * only — supervisor/engineer/CM rollups intentionally do not carry this.
     */
    @Column(name = "general_expense_amount", precision = 19, scale = 2)
    private BigDecimal generalExpenseAmount;

    /** Snapshot of the month total used to derive the daily proration. */
    @Column(name = "general_expense_monthly_total", precision = 19, scale = 2)
    private BigDecimal generalExpenseMonthlyTotal;

    /** Serialised list of {@code SectionLine} entries used by the PM DBS UI. */
    @Column(name = "general_expense_lines_json", columnDefinition = "TEXT")
    private String generalExpenseLinesJson;

    @Column(name = "boq_for_the_day_amount", precision = 19, scale = 2)
    private BigDecimal boqForTheDayAmount;

    @Column(name = "boq_planned_amount", precision = 19, scale = 2)
    private BigDecimal boqPlannedAmount;

    @Column(name = "boq_achieved_amount", precision = 19, scale = 2)
    private BigDecimal boqAchievedAmount;

    /** Phase 7: sum of {@code direct_cost} across contributing supervisor rows. */
    @Column(name = "direct_cost", precision = 18, scale = 4)
    private BigDecimal directCost;

    /** Phase 7: sum of {@code prelim_cost} across contributing supervisor rows. */
    @Column(name = "prelim_cost", precision = 18, scale = 4)
    private BigDecimal prelimCost;

    /** Phase 7: convenience field {@code = directCost + prelimCost}. */
    @Column(name = "total_cost_incl_prelims", precision = 18, scale = 4)
    private BigDecimal totalCostInclPrelims;

    /** Phase 7: {@code boqAchievedAmount / boqPlannedAmount * 100} (percentage, 0..100). */
    @Column(name = "pct_achieved", precision = 8, scale = 4)
    private BigDecimal pctAchieved;

    @Column(name = "total_expense", precision = 19, scale = 2)
    private BigDecimal totalExpense;

    @Column(name = "total_income", precision = 19, scale = 2)
    private BigDecimal totalIncome;

    @Column(name = "contribution", precision = 19, scale = 2)
    private BigDecimal contribution;

    @Column(name = "contribution_pct", precision = 9, scale = 4)
    private BigDecimal contributionPct;

    @Column(name = "recomputed_at")
    private Instant recomputedAt;
}
