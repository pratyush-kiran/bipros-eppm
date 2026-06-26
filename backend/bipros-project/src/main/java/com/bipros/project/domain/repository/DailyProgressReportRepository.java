package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

@Repository
public interface DailyProgressReportRepository extends JpaRepository<DailyProgressReport, UUID> {

  List<DailyProgressReport> findByProjectIdOrderByReportDateAscIdAsc(UUID projectId);

  /** APPROVED-only variant — same as {@link #findByProjectIdOrderByReportDateAscIdAsc} but restricted to APPROVED DPRs. */
  List<DailyProgressReport> findByProjectIdAndApprovalStatusOrderByReportDateAscIdAsc(
      UUID projectId, DprApprovalStatus approvalStatus);

  /**
   * Used by the create path to reject duplicate DPRs for the same (project, day, activity).
   * The ledger {@code daily_activity_resource_outputs} has a unique key on
   * {@code (project_id, output_date, activity_id, resource_id)} — two DPRs that touch the same
   * activity on the same day with overlapping resources collide on save. Catch it up front so
   * the user sees a clear "edit the existing DPR" message instead of a constraint violation.
   */
  Optional<DailyProgressReport> findFirstByProjectIdAndReportDateAndActivityId(
      UUID projectId, LocalDate reportDate, UUID activityId);

  /**
   * Multi-supervisor variant: an activity can have multiple supervisors, each filing their own
   * DPR for the same day. The uniqueness key narrows to {@code (project, date, activity,
   * supervisor_user_id)} — the same supervisor can't file twice, but two different supervisors
   * on the same activity/day are allowed. Resource-overlap collisions across those two DPRs
   * are still caught by the ledger's unique key on
   * {@code (project_id, output_date, activity_id, resource_id)}.
   */
  Optional<DailyProgressReport> findFirstByProjectIdAndReportDateAndActivityIdAndSupervisorUserId(
      UUID projectId, LocalDate reportDate, UUID activityId, UUID supervisorUserId);

  List<DailyProgressReport> findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
      UUID projectId, LocalDate from, LocalDate to);

  /** APPROVED-only variant — same as {@link #findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc} but restricted to APPROVED DPRs. */
  List<DailyProgressReport> findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
      UUID projectId, DprApprovalStatus approvalStatus, LocalDate from, LocalDate to);

  List<DailyProgressReport> findByProjectIdAndActivityNameIgnoreCaseOrderByReportDateAsc(
      UUID projectId, String activityName);

  long countBySupervisorUserIdAndReportDateGreaterThanEqual(
      UUID supervisorUserId, LocalDate sinceInclusive);

  /**
   * Sum of qty_executed for the (project, activityName) up to and including {@code reportDate}.
   * Used to compute cumulativeQty without storing it. Returns null when there are no rows in
   * the window — callers should treat null as zero.
   */
  @Query("""
      select coalesce(sum(d.qtyExecuted), 0)
      from DailyProgressReport d
      where d.projectId = :projectId
        and lower(d.activityName) = lower(:activityName)
        and d.reportDate <= :reportDate
      """)
  BigDecimal sumQtyExecutedThroughDate(
      @Param("projectId") UUID projectId,
      @Param("activityName") String activityName,
      @Param("reportDate") LocalDate reportDate);

  /**
   * This activity's OWN Σ qty_executed across its BOQ-linked DPRs (BOQ item must have a
   * positive boq_qty). Numerator for the per-activity BOQ progress (precedence #1) — note this
   * is the activity's own workdone, NOT the BOQ's cross-activity {@code qty_executed_to_date},
   * so two activities sharing a BOQ each get their own share. Never null.
   */
  @Query(value = """
      SELECT COALESCE(SUM(d.qty_executed), 0)
      FROM project.daily_progress_reports d
      JOIN project.boq_items b ON b.id = d.boq_item_id
      WHERE d.activity_id = :activityId
        AND b.boq_qty IS NOT NULL AND b.boq_qty > 0
      """, nativeQuery = true)
  BigDecimal sumActivityWorkdoneOnBoq(@Param("activityId") UUID activityId);

  /** APPROVED-only variant — same as {@link #sumActivityWorkdoneOnBoq} but restricted to APPROVED DPRs. */
  @Query(value = """
      SELECT COALESCE(SUM(d.qty_executed), 0)
      FROM project.daily_progress_reports d
      JOIN project.boq_items b ON b.id = d.boq_item_id
      WHERE d.activity_id = :activityId
        AND b.boq_qty IS NOT NULL AND b.boq_qty > 0
        AND d.approval_status = 'APPROVED'
      """, nativeQuery = true)
  BigDecimal sumActivityWorkdoneOnBoqApproved(@Param("activityId") UUID activityId);

  /**
   * Σ boq_qty of the DISTINCT BOQ items referenced by this activity's DPRs (only positive
   * boq_qty). Denominator for the per-activity BOQ progress. A positive result means the
   * activity is "BOQ-driven" and BOQ workdone takes precedence over its percentCompleteType.
   * Never null.
   */
  @Query(value = """
      SELECT COALESCE(SUM(b.boq_qty), 0)
      FROM project.boq_items b
      WHERE b.id IN (
        SELECT DISTINCT d.boq_item_id FROM project.daily_progress_reports d
        WHERE d.activity_id = :activityId AND d.boq_item_id IS NOT NULL)
        AND b.boq_qty IS NOT NULL AND b.boq_qty > 0
      """, nativeQuery = true)
  BigDecimal sumLinkedBoqQty(@Param("activityId") UUID activityId);

  /** APPROVED-only variant — same as {@link #sumLinkedBoqQty} but restricted to APPROVED DPRs. */
  @Query(value = """
      SELECT COALESCE(SUM(b.boq_qty), 0)
      FROM project.boq_items b
      WHERE b.id IN (
        SELECT DISTINCT d.boq_item_id FROM project.daily_progress_reports d
        WHERE d.activity_id = :activityId AND d.boq_item_id IS NOT NULL
          AND d.approval_status = 'APPROVED')
        AND b.boq_qty IS NOT NULL AND b.boq_qty > 0
      """, nativeQuery = true)
  BigDecimal sumLinkedBoqQtyApproved(@Param("activityId") UUID activityId);

  // ---- From-scratch rebuild queries (Task 1: DPR Data Repair) ----

  /** All DPRs for a project — used by the data-repair orchestrator for full-project scans. */
  List<DailyProgressReport> findByProjectId(UUID projectId);

  @org.springframework.data.jpa.repository.Query(
      "select min(d.reportDate) from DailyProgressReport d where d.projectId = :projectId")
  java.util.Optional<java.time.LocalDate> findMinReportDate(
      @org.springframework.data.repository.query.Param("projectId") UUID projectId);

  @org.springframework.data.jpa.repository.Query(
      "select max(d.reportDate) from DailyProgressReport d where d.projectId = :projectId")
  java.util.Optional<java.time.LocalDate> findMaxReportDate(
      @org.springframework.data.repository.query.Param("projectId") UUID projectId);

  /**
   * Absolute sum of qty_executed across all DPRs for a (project, boqItem).
   * Used by the from-scratch BOQ rebuild to set qtyExecutedToDate idempotently.
   */
  @org.springframework.data.jpa.repository.Query(
      "select coalesce(sum(d.qtyExecuted), 0) from DailyProgressReport d "
          + "where d.projectId = :projectId and d.boqItemId = :boqItemId")
  java.math.BigDecimal sumQtyExecutedByBoqItemId(
      @org.springframework.data.repository.query.Param("projectId") UUID projectId,
      @org.springframework.data.repository.query.Param("boqItemId") UUID boqItemId);

  /** APPROVED-only variant — same as {@link #sumQtyExecutedByBoqItemId} but restricted to APPROVED DPRs. */
  @org.springframework.data.jpa.repository.Query(
      "select coalesce(sum(d.qtyExecuted), 0) from DailyProgressReport d "
          + "where d.projectId = :projectId and d.boqItemId = :boqItemId "
          + "and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED")
  java.math.BigDecimal sumQtyExecutedByBoqItemIdApproved(
      @org.springframework.data.repository.query.Param("projectId") UUID projectId,
      @org.springframework.data.repository.query.Param("boqItemId") UUID boqItemId);

  /**
   * Null out the supervisor FK when the underlying user is deleted. {@code supervisorName}
   * stays put because the column is NOT NULL and the display snapshot is still valid history.
   */
  @Modifying
  @Query("UPDATE DailyProgressReport d SET d.supervisorUserId = null "
      + "WHERE d.supervisorUserId = :userId")
  int detachSupervisor(@Param("userId") UUID userId);

  /**
   * Distinct supervisor identities that filed any DPR for {@code (projectId, date)}.
   * Used by the DBS rollup listener to find which supervisor day-rows need recomputing
   * after a deployment/material event (those events don't carry the supervisor identity).
   * Null supervisors are excluded — the caller appends a {@code null} entry when needed.
   */
  @Query("SELECT DISTINCT d.supervisorUserId FROM DailyProgressReport d "
      + "WHERE d.projectId = :projectId AND d.reportDate = :date "
      + "AND d.supervisorUserId IS NOT NULL")
  List<UUID> findDistinctSupervisorUserIdsByProjectAndDate(
      @Param("projectId") UUID projectId, @Param("date") LocalDate date);

  /** APPROVED-only variant — same as {@link #findDistinctSupervisorUserIdsByProjectAndDate} but restricted to APPROVED DPRs. */
  @Query("SELECT DISTINCT d.supervisorUserId FROM DailyProgressReport d "
      + "WHERE d.projectId = :projectId AND d.reportDate = :date "
      + "AND d.supervisorUserId IS NOT NULL "
      + "AND d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED")
  List<UUID> findDistinctSupervisorUserIdsByProjectAndDateApproved(
      @Param("projectId") UUID projectId, @Param("date") LocalDate date);

  /** Actual DPR count for the (project, date) — drives the PM-tab "DPRs" KPI honestly. */
  long countByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

  /**
   * Most-recent distinct report dates for the project within an optional [from,to] window and
   * strictly older than an optional {@code before} cursor, optionally narrowed to one activity.
   * Ordered newest-first; pass a {@code Pageable} of size {@code days+1} to detect "has more".
   */
  @Query("""
      select distinct d.reportDate from DailyProgressReport d
      where d.projectId = :projectId
        and (cast(:from as date) is null or d.reportDate >= :from)
        and (cast(:to as date) is null or d.reportDate <= :to)
        and (cast(:before as date) is null or d.reportDate < :before)
        and (cast(:activity as string) is null or lower(d.activityName) = lower(cast(:activity as string)))
      order by d.reportDate desc
      """)
  List<LocalDate> findDistinctReportDatesDesc(
      @Param("projectId") UUID projectId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("before") LocalDate before,
      @Param("activity") String activity,
      Pageable pageable);

  /** All DPR rows for the given set of report dates, newest-first, optionally one activity. */
  @Query("""
      select d from DailyProgressReport d
      where d.projectId = :projectId
        and d.reportDate in :dates
        and (cast(:activity as string) is null or lower(d.activityName) = lower(cast(:activity as string)))
      order by d.reportDate desc, d.id asc
      """)
  List<DailyProgressReport> findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(
      @Param("projectId") UUID projectId,
      @Param("dates") Collection<LocalDate> dates,
      @Param("activity") String activity);

  /**
   * Refresh the denormalized {@code activityName} snapshot on every DPR for {@code activityId}.
   * Called from the {@code ActivityUpdatedEvent} listener so renames in the Activities tab
   * propagate to the DPR list group headers, which group on this column.
   */
  @Modifying
  @Query("UPDATE DailyProgressReport d SET d.activityName = :newName "
      + "WHERE d.activityId = :activityId")
  int renameActivity(@Param("activityId") UUID activityId, @Param("newName") String newName);

  /**
   * Earliest {@code reportDate} among APPROVED DPRs for the given activity.
   * Used by {@code ActivityStartOnFirstDprListener} to gate NOT_STARTED → IN_PROGRESS on
   * approval (not mere submission). Returns {@link Optional#empty()} when no APPROVED DPR exists.
   */
  @Query("SELECT MIN(d.reportDate) FROM DailyProgressReport d "
      + "WHERE d.activityId = :activityId "
      + "AND d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED")
  Optional<LocalDate> findEarliestApprovedReportDateForActivity(@Param("activityId") UUID activityId);

  // ─── Approval queue finders ─────────────────────────────────────────────────────

  /** SUBMITTED DPRs assigned to a specific approver, ordered by report date (oldest first). */
  List<DailyProgressReport> findByProjectIdAndApprovalStatusAndAssignedApproverUserIdOrderByReportDateAsc(
      UUID projectId, DprApprovalStatus approvalStatus, UUID assignedApproverUserId);

  /** SUBMITTED DPRs with no assigned approver, ordered by report date (oldest first). */
  List<DailyProgressReport> findByProjectIdAndApprovalStatusAndAssignedApproverUserIdIsNullOrderByReportDateAsc(
      UUID projectId, DprApprovalStatus approvalStatus);

  // ─── SLA escalation finder ───────────────────────────────────────────────────

  /**
   * Returns SUBMITTED DPRs that were submitted before {@code submittedAtBefore} and have not yet
   * been escalated ({@code escalatedAt} is null). Used by {@link com.bipros.api.scheduling.DprApprovalSlaEscalationJob}
   * to find DPRs pending approval past the SLA window.
   */
  List<DailyProgressReport> findByApprovalStatusAndSubmittedAtBeforeAndEscalatedAtIsNull(
      DprApprovalStatus approvalStatus, Instant submittedAtBefore);
}
