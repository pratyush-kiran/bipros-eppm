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
import java.util.UUID;

/**
 * Planned-side budget for ad-hoc expenses on an activity. One row per
 * (project, activity, category) holding the PM-set planned amount. Actuals roll up from
 * {@code dbs.dbs_manual_expenses} via the same category_id; variance is computed at read time.
 *
 * <p>Frozen when the parent activity is LOCKED — same lock check that gates Resource Plan edits.
 *
 * <p>Lives in bipros-resource (not bipros-activity) because the budget references
 * {@link ExpenseCategory}, which lives here too. Storing the entity here avoids a
 * module dependency cycle (bipros-resource → bipros-activity already exists). The DB table
 * is still in the {@code activity} schema since it's conceptually an activity attribute.
 */
@Entity
@Table(
    name = "activity_expense_budget",
    schema = "activity",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_activity_expense_budget_unique",
            columnNames = {"project_id", "activity_id", "category_id"})
    },
    indexes = {
        @Index(name = "idx_aeb_activity", columnList = "activity_id"),
        @Index(name = "idx_aeb_project_category", columnList = "project_id, category_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityExpenseBudget extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "activity_id", nullable = false)
  private UUID activityId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "planned_amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal plannedAmount;

  @Column(length = 500)
  private String notes;
}
