package com.bipros.dbs.listener;

import com.bipros.dbs.domain.event.DbsManualExpenseSavedEvent;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AFTER_COMMIT listener that re-runs the DBS rollup for the (project, date) of a saved
 * manual expense. Mirrors {@code DbsRecomputeListener.recomputeProjectForDate} so the
 * Section G total + Total Expense surface immediately on every tier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsManualExpenseRecomputeListener {

  private final DbsAggregationService aggregationService;
  private final ProjectTeamService projectTeamService;
  private final DailyProgressReportRepository dprRepository;

  @PostConstruct
  void logBindings() {
    log.info("DbsManualExpenseRecomputeListener bound: DbsManualExpenseSavedEvent (phase=AFTER_COMMIT)");
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onManualExpenseSaved(DbsManualExpenseSavedEvent e) {
    log.info("Manual expense saved → recompute DBS projectId={} date={} activityId={}",
        e.projectId(), e.reportDate(), e.activityId());
    try {
      // Walk every supervisor with a DPR on (project, date) so all sibling rows refresh
      // (mirrors the DPR fan-out semantics) and a recompute also runs even if no DPRs
      // exist (project-level rollup must still surface the manual entry).
      List<UUID> supervisorIds = new ArrayList<>(
          dprRepository.findDistinctSupervisorUserIdsByProjectAndDate(e.projectId(), e.reportDate()));
      if (supervisorIds.isEmpty()) {
        supervisorIds.add(null);
      }
      for (UUID sup : supervisorIds) {
        aggregationService.recomputeSupervisorDay(e.projectId(), sup, e.reportDate());
        if (sup != null) {
          UUID engineer = projectTeamService.resolveEngineerFor(e.projectId(), sup).orElse(null);
          if (engineer != null) {
            aggregationService.recomputeEngineerDay(e.projectId(), engineer, e.reportDate());
          }
          UUID cm = projectTeamService.resolveCmFor(e.projectId(), sup).orElse(null);
          if (cm != null) {
            aggregationService.recomputeCmDay(e.projectId(), cm, e.reportDate());
          }
        }
      }
      aggregationService.recomputeProjectDay(e.projectId(), e.reportDate());
    } catch (Exception ex) {
      // Swallow so the parent commit isn't rolled back. Inspect logs to debug.
      log.warn("DBS recompute after manual-expense save failed projectId={} date={}",
          e.projectId(), e.reportDate(), ex);
    }
  }
}
