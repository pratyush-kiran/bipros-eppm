package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Updates {@code percentComplete} for DURATION-typed activities on every DPR CREATED/UPDATED
 * event so EV is non-zero without waiting for the nightly {@code DurationPercentCompleteJob}.
 *
 * <p>Formula (P6-compatible): {@code (reportDate - actualStartDate + 1) / originalDuration * 100},
 * capped at 99.99. The update is monotonically forward — we only write a higher value than what
 * is already stored (a later DPR always covers a longer elapsed span).
 *
 * <p>The nightly job remains the owner for activities where no DPR was filed that day;
 * this listener is additive and does not replace it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityDurationPercentCompleteListener {

    private final ActivityRepository activityRepository;
    private final PercentCompleteCalculator calculator;
    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDprSubmitted(DprSubmittedEvent event) {
        // DELETE does not advance progress — skip.
        if (event.eventType() == DprMutationType.DELETED) {
            return;
        }
        if (event.activityId() == null) {
            return;
        }
        if (event.reportDate() == null) {
            return;
        }

        Activity activity = activityRepository.findById(event.activityId()).orElse(null);
        if (activity == null) {
            log.debug("ActivityDurationPercentCompleteListener: activity {} not found, skipping",
                    event.activityId());
            return;
        }
        if (activity.getPercentCompleteType() != PercentCompleteType.DURATION) {
            return;
        }
        if (activity.getActualStartDate() == null) {
            log.debug("ActivityDurationPercentCompleteListener: activity {} has no actualStartDate, skipping",
                    activity.getId());
            return;
        }

        PercentCompleteCalculator.Result result = calculator.calculate(
                activity, null, null, event.reportDate());

        if (result.isKeepPrior()) {
            return;
        }

        Double computed = result.percent();
        Double current = activity.getPercentComplete();

        // Only advance — never regress due to a back-dated DPR.
        if (current != null && current >= computed) {
            log.debug("ActivityDurationPercentCompleteListener: activity {} current={}% >= computed={}%, skipping",
                    activity.getId(), current, computed);
            return;
        }

        Double oldPercent = current;
        activity.setDurationPercentComplete(computed);
        activity.setPercentComplete(computed);
        if (result.status() != null && result.status() != activity.getStatus()) {
            activity.setStatus(result.status());
        }

        activityRepository.save(activity);
        log.info("DURATION percent on DPR: activity={} {}% -> {}% (reportDate={})",
                activity.getId(), oldPercent, computed, event.reportDate());

        auditService.logUpdate("Activity", activity.getId(), "percentComplete", oldPercent, computed);
    }
}
