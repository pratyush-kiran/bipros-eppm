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

/**
 * Material Role Variant — one row per priceable material variant identified by
 * (Role, Spec/Grade) directly owned by a {@link com.bipros.resource.domain.model.ResourceRole}.
 * Replaces the old {@code MaterialRateMaster} table. Spec/Grade is free text
 * (e.g. "OPC 53", "12mm rebar", "20mm aggregate").
 */
@Entity
@Table(
    name = "material_role_variants",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_material_role_variant",
            columnNames = {"role_id", "spec_grade"})
    },
    indexes = {
        @Index(name = "idx_material_role_variant_role", columnList = "role_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialRoleVariant extends BaseEntity {

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "spec_grade", nullable = false, length = 200)
  private String specGrade;

  @Column(nullable = false, length = 30)
  private String unit;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal rate;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
