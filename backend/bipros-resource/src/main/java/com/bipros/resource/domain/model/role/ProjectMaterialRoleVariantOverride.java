package com.bipros.resource.domain.model.role;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Per-project override of a {@link MaterialRoleVariant}. */
@Entity
@Table(
    name = "project_material_role_variant_override",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_proj_mat_role_variant_ovr",
            columnNames = {"project_id", "material_role_variant_id"})
    },
    indexes = {
        @Index(name = "idx_pmrv_project", columnList = "project_id"),
        @Index(name = "idx_pmrv_variant", columnList = "material_role_variant_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMaterialRoleVariantOverride extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "material_role_variant_id", nullable = false)
  private UUID materialRoleVariantId;

  @Column(name = "override_rate", nullable = false, precision = 19, scale = 4)
  private BigDecimal overrideRate;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
