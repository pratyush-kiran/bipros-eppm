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
 * Per-(project, supervisor, date) DBS rollup. Carries the six section totals plus
 * cached JSON line breakdowns so the UI accordions can render without re-querying
 * the underlying OLTP source tables. Upserted by {@code DbsAggregationService}.
 *
 * <p>{@code supervisorUserId} may be null when the supervisor on a source DPR is a
 * free-text "Other" entry; in that case the row aggregates all unattributed work for
 * the project on that date.
 */
@Entity
@Table(name = "dbs_daily_supervisor", schema = "dbs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsDailySupervisor extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "supervisor_user_id")
    private UUID supervisorUserId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "engineer_user_id")
    private UUID engineerUserId;

    /**
     * Denormalised snapshot of the supervisor's Construction Manager at the moment of
     * recompute. Set via {@code ProjectTeamService.resolveCmFor(...)} when this row is
     * (re)computed. Historical rows do not auto-update on team re-orgs — deliberate so
     * past DBS reports stay stable.
     */
    @Column(name = "construction_manager_user_id")
    private UUID constructionManagerUserId;

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

    @Column(name = "boq_for_the_day_amount", precision = 19, scale = 2)
    private BigDecimal boqForTheDayAmount;

    @Column(name = "boq_planned_amount", precision = 19, scale = 2)
    private BigDecimal boqPlannedAmount;

    @Column(name = "boq_achieved_amount", precision = 19, scale = 2)
    private BigDecimal boqAchievedAmount;

    /**
     * Phase 7: BOQ value for the day attributable to non-preliminary activities. Sum of
     * line amounts (qty × rate) across DPR rows whose underlying activity has
     * {@code is_preliminary = false}. Together with {@link #prelimCost} sums to
     * {@link #boqForTheDayAmount} (modulo rounding).
     */
    @Column(name = "direct_cost", precision = 18, scale = 4)
    private BigDecimal directCost;

    /**
     * Phase 7: BOQ value for the day attributable to preliminary activities
     * (mobilisation, site setup, diversions, bonds, etc.).
     */
    @Column(name = "prelim_cost", precision = 18, scale = 4)
    private BigDecimal prelimCost;

    /** Phase 7: convenience field {@code = directCost + prelimCost}. */
    @Column(name = "total_cost_incl_prelims", precision = 18, scale = 4)
    private BigDecimal totalCostInclPrelims;

    /**
     * Phase 7: cumulative BOQ progress as a percentage of plan —
     * {@code boqAchievedAmount / boqPlannedAmount * 100}. Stored as a percentage
     * (0..100) with four decimal places.
     */
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

    @Column(name = "material_lines_json", columnDefinition = "TEXT")
    private String materialLinesJson;

    @Column(name = "manpower_lines_json", columnDefinition = "TEXT")
    private String manpowerLinesJson;

    @Column(name = "admin_lines_json", columnDefinition = "TEXT")
    private String adminLinesJson;

    @Column(name = "machinery_lines_json", columnDefinition = "TEXT")
    private String machineryLinesJson;

    @Column(name = "fuel_lines_json", columnDefinition = "TEXT")
    private String fuelLinesJson;

    @Column(name = "boq_lines_json", columnDefinition = "TEXT")
    private String boqLinesJson;

    @Column(name = "subcontract_lines_json", columnDefinition = "TEXT")
    private String subcontractLinesJson;

    @Column(name = "recomputed_at")
    private Instant recomputedAt;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;
}
