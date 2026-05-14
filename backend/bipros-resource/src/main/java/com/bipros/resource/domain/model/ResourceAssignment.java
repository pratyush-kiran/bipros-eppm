package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(
    name = "resource_assignments",
    schema = "resource",
    indexes = {
        @Index(name = "idx_assignment_activity_id", columnList = "activity_id"),
        @Index(name = "idx_assignment_resource_id", columnList = "resource_id"),
        @Index(name = "idx_assignment_project_id", columnList = "project_id"),
        @Index(name = "idx_assignment_planned_start_date", columnList = "planned_start_date"),
        @Index(name = "idx_assignment_planned_finish_date", columnList = "planned_finish_date")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceAssignment extends BaseEntity {

  @Column(name = "activity_id", nullable = false)
  private UUID activityId;

  @Column(name = "resource_id")
  private UUID resourceId;

  @Column(name = "role_id")
  private UUID roleId;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  /**
   * P6-style "Budgeted" units — the original commitment, frozen at assignment creation. Does not
   * change when the planner re-plans {@link #plannedUnits}; only updated by an explicit
   * "Re-budget" action so the variance dashboard can compare current planned vs. original budget.
   * Backfilled on existing rows via Liquibase to {@code plannedUnits} for the migration.
   */
  @Column(name = "budgeted_units")
  private Double budgetedUnits;

  /**
   * P6-style "Budgeted" cost — original committed value. See {@link #budgetedUnits} for the same
   * semantics applied to monetary values.
   */
  @Column(name = "budgeted_cost", precision = 19, scale = 4)
  private BigDecimal budgetedCost;

  @Column(name = "planned_units")
  private Double plannedUnits;

  @Column(name = "actual_units")
  private Double actualUnits;

  @Column(name = "remaining_units")
  private Double remainingUnits;

  @Column(name = "at_completion_units")
  private Double atCompletionUnits;

  @Column(name = "planned_cost", precision = 19, scale = 4)
  private BigDecimal plannedCost;

  @Column(name = "actual_cost", precision = 19, scale = 4)
  private BigDecimal actualCost;

  @Column(name = "remaining_cost", precision = 19, scale = 4)
  private BigDecimal remainingCost;

  @Column(name = "at_completion_cost", precision = 19, scale = 4)
  private BigDecimal atCompletionCost;

  @Column(name = "rate_type", length = 50)
  private String rateType;

  @Column(name = "resource_curve_id")
  private UUID resourceCurveId;

  @Column(name = "planned_start_date")
  private LocalDate plannedStartDate;

  @Column(name = "planned_finish_date")
  private LocalDate plannedFinishDate;

  @Column(name = "actual_start_date")
  private LocalDate actualStartDate;

  @Column(name = "actual_finish_date")
  private LocalDate actualFinishDate;

  /**
   * Variant FK — exactly one of these three is populated based on role's resource type.
   * Replaces the legacy {@code resourceId} pointer in the role-only model.
   */
  @Column(name = "manpower_role_rate_id")
  private UUID manpowerRoleRateId;

  @Column(name = "equipment_role_variant_id")
  private UUID equipmentRoleVariantId;

  @Column(name = "material_role_variant_id")
  private UUID materialRoleVariantId;

  /** Headcount for manpower/equipment demand; null for material. */
  @Column(name = "headcount")
  private Integer headcount;

  /** Duration in the variant's rate-unit (Day/Hour). Null for material. */
  @Column(name = "duration", precision = 19, scale = 4)
  private BigDecimal duration;

  /** Quantity for material demand; null for manpower/equipment. */
  @Column(name = "quantity", precision = 19, scale = 4)
  private BigDecimal quantity;

  /** Effective rate snapshot — captured at assignment time for audit / cost stability. */
  @Column(name = "effective_rate", precision = 19, scale = 4)
  private BigDecimal effectiveRate;

  /** Unit copied from the variant at assignment time (e.g. Day, Hour, MT, Bag). */
  @Column(name = "unit", length = 30)
  private String unit;
}
