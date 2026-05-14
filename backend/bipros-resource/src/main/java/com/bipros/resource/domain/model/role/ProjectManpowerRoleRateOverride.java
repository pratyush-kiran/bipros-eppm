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
 * Per-project override of a {@link ManpowerRoleRate}. When present and {@code active},
 * the {@code RoleRateResolver} returns this row's {@code overrideRate} instead of the
 * variant's default rate. Used by projects that pay a non-standard rate for a given
 * (Role, Skill Level, Grade) combination.
 */
@Entity
@Table(
    name = "project_manpower_role_rate_override",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_proj_manpower_role_rate_ovr",
            columnNames = {"project_id", "manpower_role_rate_id"})
    },
    indexes = {
        @Index(name = "idx_pmrro_project", columnList = "project_id"),
        @Index(name = "idx_pmrro_variant", columnList = "manpower_role_rate_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectManpowerRoleRateOverride extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "manpower_role_rate_id", nullable = false)
  private UUID manpowerRoleRateId;

  @Column(name = "override_rate", nullable = false, precision = 19, scale = 4)
  private BigDecimal overrideRate;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
