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
 * Equipment / PMV line item under a {@link DailyProgressReport} row — captures fleet number,
 * working / idle / breakdown hours, and fuel for one piece (or batch) of equipment on that day.
 */
@Entity
@Table(
    name = "dpr_equipment",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_equipment_dpr", columnList = "dpr_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprEquipment extends BaseEntity {

    @Column(name = "dpr_id", nullable = false)
    private UUID dprId;

    /** Soft FK to {@code resource.resource_assignments.id}. */
    @Column(name = "resource_assignment_id")
    private UUID resourceAssignmentId;

    /** Denormalised snapshot of the assigned resource for display when the assignment is later deleted. */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "equipment_type", nullable = false, length = 100)
    private String equipmentType;

    /** Fleet/asset number, e.g. {@code Exc-38}, {@code W/L-58}, {@code Tipper-104}. */
    @Column(name = "fleet_no", length = 50)
    private String fleetNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "ownership", length = 20)
    private EquipmentOwnership ownership;

    @Column(name = "nos")
    private Integer nos;

    @Column(name = "working_hours", precision = 6, scale = 2)
    private BigDecimal workingHours;

    @Column(name = "idle_hours", precision = 6, scale = 2)
    private BigDecimal idleHours;

    @Column(name = "breakdown_hours", precision = 6, scale = 2)
    private BigDecimal breakdownHours;

    @Column(name = "fuel_litres", precision = 10, scale = 2)
    private BigDecimal fuelLitres;

    @Column(name = "operator_name", length = 150)
    private String operatorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", length = 20)
    private EquipmentAvailability availabilityStatus;

    @Column(name = "unit_rate", precision = 19, scale = 4)
    private java.math.BigDecimal unitRate;

    @Column(name = "unit_rate_basis", length = 20)
    private String unitRateBasis;

    @Column(name = "line_cost", precision = 19, scale = 2)
    private java.math.BigDecimal lineCost;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Role-only model: which {@code EquipmentRoleVariant} was used. Nullable for legacy rows. */
    @Column(name = "equipment_role_variant_id")
    private UUID equipmentRoleVariantId;

    @Column(name = "role_id")
    private UUID roleId;
}
