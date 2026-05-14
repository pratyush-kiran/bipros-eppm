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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Supervisor Daily Progress Report row: one entry per (project, date, chainage range, activity).
 * Captures what was physically executed that day.
 *
 * <p>{@code supervisorResourceId} is a soft FK to the {@code resource} schema (Resources with
 * role.code in {@code SUPERVISOR}/{@code FOREMAN}); {@code supervisorName} stays as a
 * denormalised display snapshot, so legacy rows still render and resource renames don't
 * rewrite history. When the FK is set the service overwrites {@code supervisorName} from the
 * resource on save. Free-text entries (off-roster supervisors) leave the FK null.
 *
 * <p>Cumulative quantity per (project, activity) is computed on read (and on event publish)
 * — there is no stored {@code cumulative_qty} column on the entity. This makes back-dated
 * edits self-consistent without rewriting later rows. BOQ qty sync is event-based via
 * {@code DprBoqSyncListener} listening for {@link com.bipros.common.event.DprSubmittedEvent}.
 */
@Entity
@Table(
    name = "daily_progress_reports",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_project_date", columnList = "project_id, report_date"),
        @Index(name = "idx_dpr_activity", columnList = "project_id, activity_name"),
        @Index(name = "idx_dpr_wbs", columnList = "wbs_node_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyProgressReport extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "report_date", nullable = false)
  private LocalDate reportDate;

  /**
   * Soft FK to {@code resource.resources.id}. When set, the service snapshots the resource's
   * name into {@link #supervisorName} on save. Null for free-text "Other" entries.
   */
  @Column(name = "supervisor_resource_id")
  private UUID supervisorResourceId;

  @Column(name = "supervisor_name", nullable = false, length = 150)
  private String supervisorName;

  /**
   * Soft FK to {@code public.users.id}. Role-only model: the supervisor is an application user,
   * not a Resource. Replaces {@link #supervisorResourceId}. The service overwrites
   * {@link #supervisorName} from the user's display name on save when this is set.
   */
  @Column(name = "supervisor_user_id")
  private UUID supervisorUserId;

  @Column(name = "chainage_from_m")
  private Long chainageFromM;

  @Column(name = "chainage_to_m")
  private Long chainageToM;

  /** Soft FK to {@code activity.activities.id}. Nullable during phase-1 migration. */
  @Column(name = "activity_id")
  private UUID activityId;

  @Column(name = "activity_name", nullable = false, length = 150)
  private String activityName;

  @Column(name = "wbs_node_id")
  private UUID wbsNodeId;

  /** Optional back-link to the BOQ item — when set, DPR save updates that item's executed qty. */
  @Column(name = "boq_item_no", length = 20)
  private String boqItemNo;

  @Column(name = "unit", nullable = false, length = 20)
  private String unit;

  @Column(name = "qty_executed", nullable = false, precision = 18, scale = 3)
  private BigDecimal qtyExecuted;

  // Cumulative qty is computed on read (see DailyProgressReportService.list) — the legacy
  // cumulative_qty column lingers in dev because ddl-auto: update doesn't drop columns; the
  // production migration drops it via a Liquibase changeset.

  @Column(name = "weather_condition", length = 100)
  private String weatherCondition;

  @Column(name = "remarks", length = 1000)
  private String remarks;

  /** Carriageway side for road / highway DPRs (LHS / RHS / CENTER). Optional. */
  @Enumerated(EnumType.STRING)
  @Column(name = "side", length = 10)
  private Side side;

  /** Free-text location landmark, e.g. "near Main Road junction". Optional. */
  @Column(name = "landmark", length = 255)
  private String landmark;

  @Column(name = "start_time")
  private LocalTime startTime;

  @Column(name = "end_time")
  private LocalTime endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "shift", length = 10)
  private Shift shift;

  /**
   * Approval state for the row. Workflow transitions are not enforced server-side yet — the
   * column is stored as the client sends it and surfaced for filtering / display.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", length = 20)
  private DprApprovalStatus approvalStatus;

  /** Top-level contractor name on the activity (subcontractor crews go on the manpower row). */
  @Column(name = "contractor_name", length = 150)
  private String contractorName;

  @Column(name = "delay_reason", length = 500)
  private String delayReason;

  @Column(name = "safety_observation", length = 500)
  private String safetyObservation;

  @Enumerated(EnumType.STRING)
  @Column(name = "safety_incident_type", length = 20)
  private SafetyIncidentType safetyIncidentType;
}
