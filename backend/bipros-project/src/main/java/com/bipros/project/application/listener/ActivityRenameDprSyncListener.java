package com.bipros.project.application.listener;

import com.bipros.common.event.ActivityUpdatedEvent;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Refreshes the denormalized {@code daily_progress_reports.activity_name} snapshot when an
 * Activity is renamed. The DPR list view groups rows by this column, so without this sync the
 * UI keeps showing the pre-rename label until the row is rewritten by hand.
 *
 * <p>Runs AFTER_COMMIT so a failure here can never roll back the rename itself. Uses
 * REQUIRES_NEW because the publishing transaction is already committed at this point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityRenameDprSyncListener {

  private final DailyProgressReportRepository dprRepository;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onActivityUpdated(ActivityUpdatedEvent event) {
    if (event.activityId() == null || event.activityName() == null) {
      return;
    }
    try {
      int updated = dprRepository.renameActivity(event.activityId(), event.activityName());
      if (updated > 0) {
        log.debug("Synced activity rename to {} DPR(s) for activity {}",
            updated, event.activityId());
      }
    } catch (Exception e) {
      log.warn("Failed to sync activity rename to DPRs for activity {}: {}",
          event.activityId(), e.getMessage(), e);
    }
  }
}
