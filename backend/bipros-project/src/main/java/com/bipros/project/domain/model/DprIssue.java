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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Field-issue log entry attached to a {@link DailyProgressReport}.
 *
 * <p>Activity / supervisor / report_date are <b>snapshotted</b> from the parent DPR at create
 * time and are deliberately <i>not</i> re-synced if the parent DPR is later edited. This keeps
 * the issue anchored to the context it was logged in (analytical accuracy: "this issue was
 * logged while crew was on Activity A on date D"). Service-level rule, not a DB trigger.
 *
 * <p>{@code project_id} is denormalised onto the row so RLS / SqlGuard can scope without a join
 * to the parent DPR. The {@code assigned_to_*} fields default to the supervisor on create and
 * are reassignable via the dedicated PATCH endpoint without re-saving the parent DPR.
 *
 * <p>Lifecycle: status transitions to {@link IssueStatus#RESOLVED} / {@link IssueStatus#CLOSED}
 * cause {@code resolved_at} to be set; transitions back to non-terminal states clear it. The
 * service is the source of truth — clients should not set {@code resolved_at} directly.
 */
@Entity
@Table(
    name = "dpr_issues",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_issue_dpr", columnList = "dpr_id"),
        @Index(name = "idx_dpr_issue_project_status", columnList = "project_id, status"),
        @Index(name = "idx_dpr_issue_activity", columnList = "project_id, activity_id"),
        @Index(name = "idx_dpr_issue_supervisor", columnList = "project_id, supervisor_resource_id"),
        @Index(name = "idx_dpr_issue_report_date", columnList = "project_id, report_date")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprIssue extends BaseEntity {

    @Column(name = "dpr_id", nullable = true, updatable = false)
    private UUID dprId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "activity_name", length = 150)
    private String activityName;

    /**
     * The supervisor who logged the issue, as a Resource id.
     *
     * @deprecated RBAC Phase 4.2 — superseded by {@link #supervisorUserId} (canonical identity is
     *     the User, not the Resource). Kept on the entity for one commit only; the column is
     *     dropped by Liquibase changeset {@code 093-2-drop-supervisor-resource-id}. New code MUST
     *     read/write {@link #supervisorUserId}.
     */
    @Deprecated
    @Column(name = "supervisor_resource_id")
    private UUID supervisorResourceId;

    /** The supervisor who logged the issue. Defaults to {@code DPR.supervisorUserId}. */
    @Column(name = "supervisor_user_id")
    private UUID supervisorUserId;

    @Column(name = "supervisor_name", length = 150)
    private String supervisorName;

    /**
     * "Who is looking into this" as a Resource id.
     *
     * @deprecated RBAC Phase 4.2 — superseded by {@link #assignedToUserId}. Dropped by Liquibase
     *     changeset {@code 093-3-drop-assigned-to-resource-id}. New code MUST read/write
     *     {@link #assignedToUserId}.
     */
    @Deprecated
    @Column(name = "assigned_to_resource_id")
    private UUID assignedToResourceId;

    /** "Who is looking into this." Defaults to the supervisor on create; reassignable via PATCH. */
    @Column(name = "assigned_to_user_id")
    private UUID assignedToUserId;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    @Column(name = "report_date", nullable = false, updatable = false)
    private LocalDate reportDate;

    @Column(name = "chainage_from_m", updatable = false)
    private Long chainageFromM;

    @Column(name = "chainage_to_m", updatable = false)
    private Long chainageToM;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private IssueCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private IssueSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssueStatus status;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_notes", length = 1000)
    private String resolutionNotes;
}
