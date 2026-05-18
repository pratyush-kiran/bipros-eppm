package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Section B of the Supervisor Daily Report: one row per resource (manpower crew or equipment unit)
 * deployed on a given day. Captures planned vs. deployed counts plus hours worked/idle — the
 * productivity signal the site team reports up to project controls.
 */
@Entity
@Table(
    name = "daily_resource_deployments",
    schema = "project",
    indexes = {
        @Index(name = "idx_drd_project_date", columnList = "project_id, log_date"),
        @Index(name = "idx_drd_resource_type", columnList = "resource_type")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyResourceDeployment extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "log_date", nullable = false)
  private LocalDate logDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type", nullable = false, length = 20)
  private DeploymentResourceType resourceType;

  @Column(name = "resource_description", nullable = false, length = 150)
  private String resourceDescription;

  /** Optional FK into bipros-resource Resource; kept as a plain UUID to avoid cross-module coupling. */
  @Column(name = "resource_id")
  private UUID resourceId;

  /** Optional FK into bipros-resource ResourceRole; plain UUID for the same reason. */
  @Column(name = "resource_role_id")
  private UUID resourceRoleId;

  @Column(name = "nos_planned")
  private Integer nosPlanned;

  /**
   * True when {@link #nosPlanned} was auto-derived from {@code ResourceAssignment.plannedUnits}
   * because the request omitted (or zeroed) the field. Stays null when the user supplied a
   * value or when the auto-resolver had no matching assignments to consult.
   */
  @Column(name = "nos_planned_auto")
  private Boolean nosPlannedAuto;

  @Column(name = "nos_deployed")
  private Integer nosDeployed;

  @Column(name = "hours_worked")
  private Double hoursWorked;

  @Column(name = "idle_hours")
  private Double idleHours;

  @Column(name = "remarks", length = 500)
  private String remarks;
}
