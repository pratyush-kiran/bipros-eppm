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
 * Per-(project, engineer, date) DBS rollup — SUM of every supervisor row whose
 * {@code engineer_user_id} matches this engineer for that day. {@code supervisorIds}
 * is a comma-joined audit trail of the contributing supervisors.
 */
@Entity
@Table(name = "dbs_daily_engineer", schema = "dbs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsDailyEngineer extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "engineer_user_id")
    private UUID engineerUserId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "supervisor_ids", columnDefinition = "TEXT")
    private String supervisorIds;

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

    @Column(name = "recomputed_at")
    private Instant recomputedAt;
}
