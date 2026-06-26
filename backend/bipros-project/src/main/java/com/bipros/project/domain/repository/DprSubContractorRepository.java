package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprSubContractor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DprSubContractorRepository extends JpaRepository<DprSubContractor, UUID> {

  List<DprSubContractor> findByDprIdOrderBySubContractorNameAsc(UUID dprId);

  List<DprSubContractor> findByDprIdIn(Collection<UUID> dprIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  void deleteByDprId(UUID dprId);

  /**
   * Σ quantity for an assignment across all DPRs. Used by
   * {@code DailyProgressReportService.recomputeScActuals} to refresh
   * {@code ActivitySubContractorAssignment.actualUnits/actualCost}.
   */
  @Query("SELECT COALESCE(SUM(d.quantity), 0) FROM DprSubContractor d "
      + "WHERE d.activitySubContractorAssignmentId = :assignmentId")
  BigDecimal sumQuantityByActivitySubContractorAssignmentId(
      @Param("assignmentId") UUID assignmentId);

  /**
   * APPROVED-only variant — joins the parent {@code DailyProgressReport} (via {@code sc.dprId = d.id})
   * so the sum is restricted to APPROVED DPRs. Used by
   * {@code DailyProgressReportService.recomputeScActuals} once it switches to approved-only.
   */
  @Query("SELECT COALESCE(SUM(sc.quantity), 0) "
      + "FROM DprSubContractor sc, DailyProgressReport d "
      + "WHERE sc.activitySubContractorAssignmentId = :assignmentId "
      + "  AND sc.dprId = d.id "
      + "  AND d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED")
  BigDecimal sumQuantityByActivitySubContractorAssignmentIdApproved(
      @Param("assignmentId") UUID assignmentId);

  /**
   * Σ dpr_sub_contractor.quantity for a project, grouped by activity (activity_id is on the
   * parent daily_progress_reports row). Used by ManpowerKpiService to subtract SC quantity
   * from qty_executed before computing Productivity Factor — so the metric reflects
   * supervisor's own crew output, not SC + crew combined.
   *
   * <p>Returns rows of {@code (UUID activityId, BigDecimal totalQty)} — an empty list when
   * the project has no sub-contractor DPR entries.
   */
  @Query("SELECT d.activityId, COALESCE(SUM(sc.quantity), 0) "
      + "FROM DprSubContractor sc, DailyProgressReport d "
      + "WHERE sc.dprId = d.id "
      + "  AND d.projectId = :projectId "
      + "  AND d.activityId IS NOT NULL "
      + "GROUP BY d.activityId")
  java.util.List<Object[]> sumQuantityByProjectGroupedByActivity(@Param("projectId") UUID projectId);

  /** APPROVED-only variant — same as {@link #sumQuantityByProjectGroupedByActivity} but restricted to APPROVED DPRs. */
  @Query("SELECT d.activityId, COALESCE(SUM(sc.quantity), 0) "
      + "FROM DprSubContractor sc, DailyProgressReport d "
      + "WHERE sc.dprId = d.id "
      + "  AND d.projectId = :projectId "
      + "  AND d.activityId IS NOT NULL "
      + "  AND d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED "
      + "GROUP BY d.activityId")
  java.util.List<Object[]> sumQuantityByProjectGroupedByActivityApproved(@Param("projectId") UUID projectId);

  /**
   * Σ dpr_sub_contractor.quantity for a project, grouped by BOQ item (boq_item_id is on the
   * parent DPR row). Used by ManpowerKpiService.computeLabourCostPerUnit to subtract SC qty
   * from the denominator so cost-per-unit reflects supervisor's crew effort only.
   */
  @Query("SELECT d.boqItemId, COALESCE(SUM(sc.quantity), 0) "
      + "FROM DprSubContractor sc, DailyProgressReport d "
      + "WHERE sc.dprId = d.id "
      + "  AND d.projectId = :projectId "
      + "  AND d.boqItemId IS NOT NULL "
      + "GROUP BY d.boqItemId")
  java.util.List<Object[]> sumQuantityByProjectGroupedByBoqItem(@Param("projectId") UUID projectId);

  /** APPROVED-only variant — same as {@link #sumQuantityByProjectGroupedByBoqItem} but restricted to APPROVED DPRs. */
  @Query("SELECT d.boqItemId, COALESCE(SUM(sc.quantity), 0) "
      + "FROM DprSubContractor sc, DailyProgressReport d "
      + "WHERE sc.dprId = d.id "
      + "  AND d.projectId = :projectId "
      + "  AND d.boqItemId IS NOT NULL "
      + "  AND d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED "
      + "GROUP BY d.boqItemId")
  java.util.List<Object[]> sumQuantityByProjectGroupedByBoqItemApproved(@Param("projectId") UUID projectId);

  List<DprSubContractor> findByActivitySubContractorAssignmentIdIn(
      Collection<UUID> assignmentIds);
}
