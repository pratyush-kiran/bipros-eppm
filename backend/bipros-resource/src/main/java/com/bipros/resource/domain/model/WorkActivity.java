package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Library / master definition of a unit of work (e.g. "Clearing & Grubbing", "Subgrade Preparation").
 *
 * <p>Distinct from project-specific {@code activity.activities}: a WorkActivity is reusable across
 * projects and is the anchor that links a {@link ProductivityNorm} to its (resource-type or
 * specific-resource) productivity. Same activity can have different norms per resource — the norm
 * row carries that scope; the activity definition is the same.
 */
@Entity
@Table(
    name = "work_activities",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_work_activity_code", columnNames = {"code"})
    },
    indexes = {
        @Index(name = "idx_work_activity_active", columnList = "active")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkActivity extends BaseEntity {

  @Column(nullable = false, length = 50, unique = true)
  private String code;

  @Column(nullable = false, length = 150)
  private String name;

  /** Default unit of measure for this activity (e.g. "Sqm", "Cum"). Norms may override. */
  @Column(name = "default_unit", length = 20)
  private String defaultUnit;

  /** Free-text discipline tag — earthwork / pavement / structures / electrical / etc. */
  @Column(length = 50)
  private String discipline;

  @Column(length = 500)
  private String description;

  @Column(name = "sort_order")
  private Integer sortOrder;

  @Column(nullable = false)
  @Default
  private Boolean active = true;

  /**
   * How Manpower and Equipment productivity norms combine into a single "expected output" figure
   * (used by the DPR productivity preview). Ignored when only one side has a norm. Defaults to
   * SERIES so the historical {@code min()} bottleneck behaviour is preserved for existing data.
   *
   * <p>{@code columnDefinition} carries the DB-side default so {@code ddl-auto: update} in dev can
   * add this NOT NULL column to a populated table without manual backfill. Prod gets the same
   * default via the matching Liquibase changeset 104.
   */
  @Enumerated(EnumType.STRING)
  @Column(
      name = "norm_combination",
      nullable = false,
      length = 16,
      columnDefinition = "VARCHAR(16) DEFAULT 'SERIES'")
  @Default
  private NormCombination normCombination = NormCombination.SERIES;
}
