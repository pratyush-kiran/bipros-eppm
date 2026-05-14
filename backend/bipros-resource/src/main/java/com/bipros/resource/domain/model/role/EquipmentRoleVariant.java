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
 * Equipment Role Variant — one row per priceable equipment variant identified by
 * (Role, Make, Model) directly owned by a {@link com.bipros.resource.domain.model.ResourceRole}.
 * Replaces the old {@code EquipmentRateMaster} table.
 */
@Entity
@Table(
    name = "equipment_role_variants",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_equipment_role_variant",
            columnNames = {"role_id", "make", "model"})
    },
    indexes = {
        @Index(name = "idx_equipment_role_variant_role", columnList = "role_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EquipmentRoleVariant extends BaseEntity {

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(nullable = false, length = 100)
  private String make;

  @Column(nullable = false, length = 100)
  private String model;

  @Column(nullable = false, length = 30)
  private String unit;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal rate;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
