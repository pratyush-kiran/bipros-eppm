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
