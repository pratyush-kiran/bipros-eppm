package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Material consumption line item under a {@link DailyProgressReport} row. Captures what was
 * consumed for the activity that day — quantity, source (quarry / yard / vendor), batch number,
 * and the supplier.
 */
@Entity
@Table(
    name = "dpr_material",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_material_dpr", columnList = "dpr_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprMaterial extends BaseEntity {

    @Column(name = "dpr_id", nullable = false)
    private UUID dprId;

    /** Soft FK to {@code resource.resource_assignments.id}. */
    @Column(name = "resource_assignment_id")
    private UUID resourceAssignmentId;

    /** Soft FK to {@code resource.material.id} for catalogue mode. */
    @Column(name = "material_id")
    private UUID materialId;

    /** Denormalised snapshot of the assigned resource for display when the assignment is later deleted. */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "material_name", nullable = false, length = 150)
    private String materialName;

    @Column(name = "quantity", precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "source", length = 150)
    private String source;

    @Column(name = "batch_no", length = 60)
    private String batchNo;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "unit_rate", precision = 19, scale = 4)
    private java.math.BigDecimal unitRate;

    @Column(name = "line_cost", precision = 19, scale = 2)
    private java.math.BigDecimal lineCost;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Role-only model: which {@code MaterialRoleVariant} was consumed. Nullable for legacy rows. */
    @Column(name = "material_role_variant_id")
    private UUID materialRoleVariantId;

    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "entered_by_role", length = 32)
    private String enteredByRole;
}
