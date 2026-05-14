package com.bipros.project.domain.model;

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
 * Manpower line item under a {@link DailyProgressReport} row. Each row records the deployment
 * of one trade/category for that activity on that day. {@code dprId} is a soft FK — lifecycle is
 * managed transactionally by {@code DailyProgressReportService} (replace-on-update semantics).
 */
@Entity
@Table(
    name = "dpr_manpower",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_manpower_dpr", columnList = "dpr_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprManpower extends BaseEntity {

    @Column(name = "dpr_id", nullable = false)
    private UUID dprId;

    /** Soft FK to {@code resource.resource_assignments.id}. */
    @Column(name = "resource_assignment_id")
    private UUID resourceAssignmentId;

    /** Denormalised snapshot of the assigned resource for display when the assignment is later deleted. */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "trade", nullable = false, length = 100)
    private String trade;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private ManpowerCategory category;

    @Column(name = "nos")
    private Integer nos;

    @Column(name = "working_hours", precision = 6, scale = 2)
    private BigDecimal workingHours;

    @Column(name = "ot_hours", precision = 6, scale = 2)
    private BigDecimal otHours;

    /** Per-row idle hours. Feeds KPI 1.2 (Idle Time Ratio) directly from DPR. */
    @Column(name = "idle_hours", precision = 6, scale = 2)
    private BigDecimal idleHours;

    @Column(name = "contractor_name", length = 150)
    private String contractorName;

    @Column(name = "unit_rate", precision = 19, scale = 4)
    private java.math.BigDecimal unitRate;

    @Column(name = "unit_rate_basis", length = 20)
    private String unitRateBasis;

    @Column(name = "line_cost", precision = 19, scale = 2)
    private java.math.BigDecimal lineCost;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /**
     * Role-only model: which {@code ManpowerRoleRate} variant was consumed for this row.
     * Soft FK to {@code resource.manpower_role_rates.id}. Nullable for legacy rows.
     */
    @Column(name = "manpower_role_rate_id")
    private UUID manpowerRoleRateId;

    /** Denormalised role id for fast filtering and rollup. */
    @Column(name = "role_id")
    private UUID roleId;
}
