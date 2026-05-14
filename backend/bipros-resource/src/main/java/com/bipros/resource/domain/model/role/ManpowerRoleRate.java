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
 * Manpower Role Rate — one row per priceable combination of (Role, Category, Grade) directly
 * owned by a {@link com.bipros.resource.domain.model.ResourceRole}. Replaces the old
 * {@code ManpowerRateMaster} chain that snapshotted rates onto individual {@code Resource}
 * instances; in the new role-only model there are no instances, so the rate book lives on the role.
 *
 * <p>Category is a {@code ManpowerCategoryMaster} FK (Skilled / Semi-Skilled / Unskilled / Staff /
 * admin-defined). Mirrors the legacy Manpower Rate Master shape — only the rate-book ownership
 * (now on the role) has changed.
 *
 * <p>Cost compute looks up an effective rate via {@code RoleRateResolver}:
 * project-level override (if any) → this row's {@code rate}.
 */
@Entity
@Table(
    name = "manpower_role_rates",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_manpower_role_rate",
            columnNames = {"role_id", "category_id", "grade_id"})
    },
    indexes = {
        @Index(name = "idx_manpower_role_rate_role", columnList = "role_id"),
        @Index(name = "idx_manpower_role_rate_category", columnList = "category_id"),
        @Index(name = "idx_manpower_role_rate_grade", columnList = "grade_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManpowerRoleRate extends BaseEntity {

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "grade_id", nullable = false)
  private UUID gradeId;

  @Column(nullable = false, length = 30)
  private String unit;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal rate;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
