package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsManualExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bipros.dbs.domain.model.DbsManualExpense.ApprovalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface DbsManualExpenseRepository extends JpaRepository<DbsManualExpense, UUID> {

  List<DbsManualExpense> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

  List<DbsManualExpense> findByProjectIdAndReportDateAndSupervisorUserId(
      UUID projectId, LocalDate reportDate, UUID supervisorUserId);

  List<DbsManualExpense> findByActivityId(UUID activityId);

  /** Pending-approval inbox query — all PENDING entries on a project, newest first. */
  List<DbsManualExpense> findByProjectIdAndApprovalStatusOrderByCreatedAtDesc(
      UUID projectId, ApprovalStatus approvalStatus);

  /**
   * Sum of APPROVED-status expense amounts for a single (project, activity). Used by
   * {@code DprActualCostLookup} to fold ad-hoc expenses into the activity's actual cost.
   */
  @Query("SELECT COALESCE(SUM(e.amount), 0) FROM DbsManualExpense e "
      + "WHERE e.projectId = :pid AND e.activityId = :aid "
      + "AND e.approvalStatus = com.bipros.dbs.domain.model.DbsManualExpense.ApprovalStatus.APPROVED")
  BigDecimal sumApprovedAmountByActivity(@Param("pid") UUID projectId,
                                         @Param("aid") UUID activityId);
}
