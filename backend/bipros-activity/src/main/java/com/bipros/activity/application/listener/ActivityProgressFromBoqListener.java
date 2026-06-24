package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Precedence #1: keeps {@code Activity.percentComplete} in sync with BOQ workdone. Fires
 * AFTER_COMMIT on every DPR mutation (the BOQ qty listeners have already updated
 * {@code BoqItem.qtyExecutedToDate}). For the DPR's activity:
 *
 * <pre>
 *   percent = (this activity's own Σ qtyExecuted on its BOQ-linked DPRs)
 *             ÷ (Σ distinct linked boqQty) × 100,  capped 100
 * </pre>
 *
 * <p>Uses the activity's OWN workdone (not the BOQ's cross-activity total), so two activities
 * sharing a BOQ each get their own share. Derives status and sets {@code actualFinishDate} on
 * reaching 100 (100 ⇔ Completed). Activities with no BOQ-linked DPR are left to their
 * percentCompleteType writers. Runs in its own transaction so a failure never rolls back the DPR.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityProgressFromBoqListener {

  private final ActivityRepository activityRepository;
  private final DailyProgressReportRepository dprRepository;
  private final PercentCompleteCalculator calculator;
  private final AuditService auditService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDprSubmitted(DprSubmittedEvent event) {
    if (event.activityId() == null) return;
    recompute(event.activityId());
  }

  private void recompute(UUID activityId) {
    Activity activity = activityRepository.findById(activityId).orElse(null);
    if (activity == null) return;

    BigDecimal boqQty = dprRepository.sumLinkedBoqQty(activityId);
    if (boqQty == null || boqQty.signum() <= 0) return; // not BOQ-driven — leave to type writers

    BigDecimal workdone = dprRepository.sumActivityWorkdoneOnBoq(activityId);

    PercentCompleteCalculator.Result result = calculator.calculateBoq(
        activity,
        workdone == null ? 0.0 : workdone.doubleValue(),
        boqQty.doubleValue(),
        LocalDate.now());
    if (result.isKeepPrior()) return;

    Double oldPct = activity.getPercentComplete();
    var oldStatus = activity.getStatus();
    if (oldPct != null && result.percent() != null
        && Math.abs(oldPct - result.percent()) < 0.01d
        && result.status() == oldStatus
        && result.forcedActualFinish() == null) {
      return; // no meaningful change
    }

    activity.setPercentComplete(result.percent());
    if (result.status() != null) {
      activity.setStatus(result.status());
    }
    if (result.forcedActualFinish() != null) {
      activity.setActualFinishDate(result.forcedActualFinish());
    }
    activityRepository.save(activity);
    log.info("ActivityProgressFromBoqListener: activity={} percentComplete {} -> {} status {} -> {}",
        activityId, oldPct, result.percent(), oldStatus, activity.getStatus());

    if (!java.util.Objects.equals(oldPct, result.percent())) {
      auditService.logUpdate("Activity", activityId, "percentComplete", oldPct, result.percent());
    }
    if (!java.util.Objects.equals(oldStatus, activity.getStatus())) {
      auditService.logUpdate("Activity", activityId, "status", oldStatus, activity.getStatus());
    }
  }
}
