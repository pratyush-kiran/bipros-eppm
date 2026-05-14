package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
    name = "productivity_norms",
    schema = "resource",
    indexes = {
        @Index(name = "idx_prod_norm_type", columnList = "norm_type"),
        @Index(name = "idx_prod_norm_work_activity", columnList = "work_activity_id"),
        @Index(name = "idx_prod_norm_resource_type", columnList = "resource_type_id"),
        @Index(name = "idx_prod_norm_resource", columnList = "resource_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductivityNorm extends BaseEntity {

  @Enumerated(EnumType.STRING)
  @Column(name = "norm_type", nullable = false, length = 20)
  private ProductivityNormType normType;

  /**
   * Master activity this norm applies to. Replaces the legacy free-text {@link #activityName}.
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "work_activity_id", nullable = false)
  private WorkActivity workActivity;

  /**
   * Default scope: when set, this norm applies to every resource of the given type
   * (e.g. all "Bull Dozer" instances). Mutually exclusive with {@link #resource}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resource_type_id")
  private ResourceType resourceType;

  /**
   * Override scope: when set, this norm applies only to one specific resource and overrides any
   * type-level norm for the same activity. Mutually exclusive with {@link #resourceType}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resource_id")
  private Resource resource;

  /**
   * Legacy free-text activity label preserved for one release cycle so old API consumers and
   * exports keep working. New code should rely on {@link #workActivity}; service layer keeps this
   * synchronised with {@code workActivity.name} on save.
   *
   * @deprecated use {@link #workActivity}; this column will be dropped in a follow-up changelog.
   */
  @Deprecated
  @Column(name = "activity_name", length = 150)
  private String activityName;

  @Column(name = "unit", nullable = false, length = 20)
  private String unit;

  /** Manpower: output per man per day; Equipment: not used. */
  @Column(name = "output_per_man_per_day", precision = 12, scale = 4)
  private BigDecimal outputPerManPerDay;

  /** Equipment: output per hour. */
  @Column(name = "output_per_hour", precision = 12, scale = 4)
  private BigDecimal outputPerHour;

  /** Manpower: number of men in a gang. Equipment: not used. */
  @Column(name = "crew_size")
  private Integer crewSize;

  /** Gang or equipment daily output; for manpower = outputPerManPerDay × crewSize. */
  @Column(name = "output_per_day", precision = 12, scale = 4)
  private BigDecimal outputPerDay;

  @Column(name = "working_hours_per_day")
  private Double workingHoursPerDay;

  @Column(name = "fuel_litres_per_hour", precision = 10, scale = 2)
  private BigDecimal fuelLitresPerHour;

  /** Equipment only: e.g. "JCB 210 (1.0 Cum Bucket)". */
  @Column(name = "equipment_spec", length = 150)
  private String equipmentSpec;

  @Column(name = "remarks", length = 500)
  private String remarks;

  /**
   * Role-based scope (new model). When set, this norm applies to a specific role (Mason,
   * Excavator, Cement). Optional skill/grade (manpower) or make/model (equipment) refine
   * the match further. Resolution chain: (role + variant fields) → (role-only) → unscoped.
   * Mutually exclusive with the legacy {@link #resourceType} / {@link #resource} fields.
   */
  @Column(name = "role_id")
  private java.util.UUID roleId;

  /** Manpower fine-grain: category FK (Skilled / Semi-Skilled / Unskilled / Staff). */
  @Column(name = "category_id")
  private java.util.UUID categoryId;

  /** Manpower fine-grain: grade FK (A/B/C). */
  @Column(name = "grade_id")
  private java.util.UUID gradeId;

  /** Equipment fine-grain: free-text make. */
  @Column(name = "make", length = 100)
  private String make;

  /** Equipment fine-grain: free-text model. */
  @Column(name = "model", length = 100)
  private String model;
}
