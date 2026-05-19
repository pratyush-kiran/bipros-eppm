package com.bipros.dbs.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * Per-(project, CM, date) DBS rollup — SUM of every supervisor row whose denormalised
 * {@code construction_manager_user_id} matches this CM for that day.
 *
 * <p>The {@code constructionManagerUserId} snapshot on the supervisor row is set at
 * supervisor-recompute time by walking the team chain via {@code resolveCmFor}, so this
 * aggregate is stable across team re-orgs (historical CM attribution doesn't shift).
 *
 * <p>Phase 4 introduces the new {@code directCost} / {@code prelimCost} /
 * {@code totalCostInclPrelims} / {@code pctAchieved} columns up-front on this brand-new
 * entity. The corresponding fields on the older supervisor/engineer/project aggregates are
 * added in Phase 7; until then the aggregation here defaults them to zero.
 */
@Entity
@Table(
    name = "dbs_daily_cm",
    schema = "dbs",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dbs_daily_cm_project_cm_date",
        columnNames = {"project_id", "cm_user_id", "report_date"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsDailyCm extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "cm_user_id", nullable = false)
    private UUID cmUserId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "manpower_amount", precision = 18, scale = 4)
    private BigDecimal manpowerAmount;

    @Column(name = "admin_amount", precision = 18, scale = 4)
    private BigDecimal adminAmount;

    @Column(name = "machinery_amount", precision = 18, scale = 4)
    private BigDecimal machineryAmount;

    @Column(name = "fuel_amount", precision = 18, scale = 4)
    private BigDecimal fuelAmount;

    @Column(name = "material_amount", precision = 18, scale = 4)
    private BigDecimal materialAmount;

    @Column(name = "direct_cost", precision = 18, scale = 4)
    private BigDecimal directCost;

    @Column(name = "prelim_cost", precision = 18, scale = 4)
    private BigDecimal prelimCost;

    @Column(name = "total_cost_incl_prelims", precision = 18, scale = 4)
    private BigDecimal totalCostInclPrelims;

    @Column(name = "boq_for_the_day_amount", precision = 18, scale = 4)
    private BigDecimal boqForTheDayAmount;

    @Column(name = "boq_planned_to_date", precision = 18, scale = 4)
    private BigDecimal boqPlannedToDate;

    @Column(name = "boq_achieved_to_date", precision = 18, scale = 4)
    private BigDecimal boqAchievedToDate;

    @Column(name = "contribution_pct", precision = 8, scale = 4)
    private BigDecimal contributionPct;

    @Column(name = "pct_achieved", precision = 8, scale = 4)
    private BigDecimal pctAchieved;

    @Column(name = "site_manager_ids", columnDefinition = "uuid[]")
    private UUID[] siteManagerIds;

    @Column(name = "engineer_ids", columnDefinition = "uuid[]")
    private UUID[] engineerIds;

    @Column(name = "supervisor_count")
    private Integer supervisorCount;

    @Column(name = "recomputed_at")
    private Instant recomputedAt;
}
