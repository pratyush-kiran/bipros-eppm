package com.bipros.dbs.domain.model;

import com.bipros.common.model.BaseEntity;
import com.bipros.project.domain.model.Shift;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 5 — Manpower Deployment Register row, one per
 * {@code (project_id, report_date, cm_user_id, trade, shift)}. Mirrors
 * {@link DbsEquipmentRegisterRow} but groups by {@code trade} (e.g. Mason, Carpenter,
 * Helper) instead of {@code equipment_type}.
 *
 * <p>{@code cm_user_id} is nullable for supervisors with no CM in the reporting chain.
 * The register is rebuilt idempotently by
 * {@code RegisterAggregationService.recompute(projectId, date)}.
 */
@Entity
@Table(
    name = "dbs_manpower_register",
    schema = "dbs",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dbs_manpower_register_unique",
        columnNames = {"project_id", "report_date", "cm_user_id", "trade", "shift"}
    ),
    indexes = {
        @Index(name = "idx_dbs_manpower_register_project_date",
               columnList = "project_id, report_date"),
        @Index(name = "idx_dbs_manpower_register_project_cm_date",
               columnList = "project_id, cm_user_id, report_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsManpowerRegisterRow extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    /** Null when the supervisor whose DPR contributed has no CM in the reporting chain. */
    @Column(name = "cm_user_id")
    private UUID cmUserId;

    @Column(name = "trade", nullable = false, length = 100)
    private String trade;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift", nullable = false, length = 8)
    private Shift shift;

    @Column(name = "count_nos", nullable = false)
    private Integer countNos;

    @Column(name = "working_hours", precision = 12, scale = 2)
    private BigDecimal workingHours;

    @Column(name = "rate", precision = 12, scale = 4)
    private BigDecimal rate;

    @Column(name = "line_cost", precision = 18, scale = 4)
    private BigDecimal lineCost;
}
