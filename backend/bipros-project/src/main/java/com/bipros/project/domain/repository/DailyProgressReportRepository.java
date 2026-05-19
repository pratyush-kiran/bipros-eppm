package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DailyProgressReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailyProgressReportRepository extends JpaRepository<DailyProgressReport, UUID> {

  List<DailyProgressReport> findByProjectIdOrderByReportDateAscIdAsc(UUID projectId);

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
}
