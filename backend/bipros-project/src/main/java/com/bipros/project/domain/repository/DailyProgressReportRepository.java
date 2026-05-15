package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DailyProgressReport;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
