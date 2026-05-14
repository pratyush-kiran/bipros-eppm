package com.bipros.activity.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "activities", schema = "activity", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"project_id", "code"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Activity extends BaseEntity {

  @Column(nullable = false, length = 20)
  private String code;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "wbs_node_id", nullable = false)
  private UUID wbsNodeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ActivityType activityType = ActivityType.TASK_DEPENDENT;

  @Enumerated(EnumType.STRING)
  @Column(name = "duration_type")
  private DurationType durationType = DurationType.FIXED_DURATION_AND_UNITS;

  @Enumerated(EnumType.STRING)
  @Column(name = "percent_complete_type")
  private PercentCompleteType percentCompleteType = PercentCompleteType.DURATION;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ActivityStatus status = ActivityStatus.NOT_STARTED;

  @Column(name = "original_duration")
  private Double originalDuration;

  @Column(name = "remaining_duration")
  private Double remainingDuration;

  @Column(name = "at_completion_duration")
  private Double atCompletionDuration;

  @Column(name = "planned_start_date")
  private LocalDate plannedStartDate;

  @Column(name = "planned_finish_date")
  private LocalDate plannedFinishDate;

  @Column(name = "early_start_date")
  private LocalDate earlyStartDate;

  @Column(name = "early_finish_date")
  private LocalDate earlyFinishDate;

  @Column(name = "late_start_date")
  private LocalDate lateStartDate;

  @Column(name = "late_finish_date")
  private LocalDate lateFinishDate;

  @Column(name = "actual_start_date")
  private LocalDate actualStartDate;

  @Column(name = "actual_finish_date")
  private LocalDate actualFinishDate;

  @Column(name = "total_float")
  private Double totalFloat;

  @Column(name = "free_float")
  private Double freeFloat;

  @Column(nullable = false)
  private Double percentComplete = 0.0;

  @Column(name = "physical_percent_complete")
  private Double physicalPercentComplete;

  @Column(name = "duration_percent_complete")
  private Double durationPercentComplete;

  @Column(name = "units_percent_complete")
  private Double unitsPercentComplete;

  @Column(name = "calendar_id")
  private UUID calendarId;

  @Column(name = "is_critical", nullable = false)
  private Boolean isCritical = false;

  @Column(name = "suspend_date")
  private LocalDate suspendDate;

  @Column(name = "resume_date")
  private LocalDate resumeDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "primary_constraint_type")
  private ConstraintType primaryConstraintType;

  @Column(name = "primary_constraint_date")
  private LocalDate primaryConstraintDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "secondary_constraint_type")
  private ConstraintType secondaryConstraintType;

  @Column(name = "secondary_constraint_date")
  private LocalDate secondaryConstraintDate;

  @Column(name = "sort_order")
  private Integer sortOrder;

  @Column(columnDefinition = "TEXT")
  private String notes;

  /** Linear referencing: chainage start in metres (e.g. 145000 = km 145+000). */
  @Column(name = "chainage_from_m")
  private Long chainageFromM;

  /** Linear referencing: chainage end in metres (e.g. 145500 = km 145+500). */
  @Column(name = "chainage_to_m")
  private Long chainageToM;

  /**
   * Soft FK to {@code public.users.id}. The team member responsible for executing the activity.
   * Drives ABAC: a TEAM_MEMBER user may only update activities where they appear here.
   */
  @Column(name = "assigned_to")
  private UUID assignedTo;

  /**
   * Soft FK to {@code public.users.id}. Higher-tier accountability (PM/PMO who signs off the
   * activity). Often populated from the project's {@code ownerId} but may differ for delegated
   * supervision.
   *
   * <p>Note: dormant — not currently wired into any UI or service flow. Reserved for a future
   * PM-level approval / sign-off concept. The field-level accountable person is captured via
   * {@link #responsibleResourceId} below (cached from {@code ResourceAssignment.isSupervisor}).
   */
  @Column(name = "responsible_user_id")
  private UUID responsibleUserId;

  /**
   * Soft FK to {@code resource.resources.id}. Cached mirror of the single
   * {@code ResourceAssignment} on this activity that has {@code isSupervisor = true}. Updated
   * by {@code ResourceAssignmentService} whenever the flag flips on any assignment for this
   * activity. NEVER set directly via the activity create/update DTO path — the assignment is
   * the source of truth, this column is a denormalised cache to keep the activity grid + DPR
   * pre-fill fast.
   */
  @Column(name = "responsible_resource_id")
  private UUID responsibleResourceId;

  /**
   * Display-snapshot of the supervisor Resource's name at the time the assignment flag was last
   * flipped. Avoids a cross-module fetch every time the activity grid loads. Goes stale if the
   * Resource is renamed — accepted limitation; mirrors {@code DailyProgressReport.supervisorName}.
   */
  @Column(name = "responsible_resource_name", length = 150)
  private String responsibleResourceName;

  /**
   * Soft FK to {@code public.users.id}. The supervisor — an application user (not a Resource)
   * who oversees execution of this activity. Replaces {@link #responsibleResourceId} in the
   * role-only model. Set/cleared via {@code PUT /v1/activities/{id}/supervisor}; backfilled
   * by Liquibase 087 for activities whose old responsibleResourceId pointed at a user-linked
   * Resource.
   */
  @Column(name = "supervisor_user_id")
  private UUID supervisorUserId;

  /**
   * Soft FK to {@code resource.work_activities.id} — the master / library activity this
   * project-specific activity is an instance of. Used by the productivity-norm lookup chain to
   * answer "for this project activity + this resource, what's the daily output norm?".
   *
   * <p>Stored as a plain UUID (no JPA {@code @ManyToOne}) to honour the no-cross-module-deps rule
   * — {@code WorkActivity} lives in {@code bipros-resource}.
   */
  @Column(name = "work_activity_id")
  private UUID workActivityId;

  /**
   * Soft FK to {@code cost.cost_accounts.id}. Optional — when set, overrides any cost account
   * inherited from the activity's WBS node (P6-style soft inheritance).
   *
   * <p>Stored as a plain UUID (no JPA {@code @ManyToOne}) to honour the no-cross-module-deps rule
   * — {@code CostAccount} lives in {@code bipros-cost}.
   */
  @Column(name = "cost_account_id")
  private UUID costAccountId;
}
