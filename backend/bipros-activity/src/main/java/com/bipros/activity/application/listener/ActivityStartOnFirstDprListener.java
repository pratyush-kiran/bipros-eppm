package com.bipros.activity.application.listener;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
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
 * Bootstraps an activity from {@code NOT_STARTED} to {@code IN_PROGRESS} the first time a DPR
 * is filed against it (A3/ACT-001).
 *
 * <p>Without this listener, {@code DurationPercentCompleteJob} (nightly) skips
 * {@code NOT_STARTED} activities, so EV stays 0 even after real work is reported.
 * The fix is minimal and idempotent: subsequent DPRs leave an already-{@code IN_PROGRESS}
 * activity untouched; percent-complete and COMPLETED transitions remain owned by the
 * existing calculator and nightly job.
 *
 * <p>Listens {@code AFTER_COMMIT} in a new transaction so it never rolls back the DPR write.
 * DELETE events are intentionally ignored — removing a DPR does not rewind activity status.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityStartOnFirstDprListener {

    private final ActivityRepository activityRepository;
    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDprSubmitted(DprSubmittedEvent event) {
        // Only react to CREATED / UPDATED — a DELETE must not rewind status.
        if (event.eventType() == DprMutationType.DELETED) {
            return;
        }
        if (event.activityId() == null) {
            // Legacy DPRs without a hard FK — nothing to update.
            return;
        }

        Activity activity = activityRepository.findById(event.activityId()).orElse(null);
        if (activity == null) {
            log.debug("ActivityStartOnFirstDprListener: activity {} not found, skipping", event.activityId());
            return;
        }
        if (activity.getStatus() != ActivityStatus.NOT_STARTED) {
            // Already IN_PROGRESS or COMPLETED — idempotent no-op.
            return;
        }

        activity.setStatus(ActivityStatus.IN_PROGRESS);
        if (activity.getActualStartDate() == null && event.reportDate() != null) {
            activity.setActualStartDate(event.reportDate());
        }

        activityRepository.save(activity);
        log.info("ActivityStartOnFirstDprListener: activity {} transitioned NOT_STARTED → IN_PROGRESS " +
                "(actualStart={})", activity.getId(), activity.getActualStartDate());

        auditService.logUpdate("Activity", activity.getId(), "status",
                ActivityStatus.NOT_STARTED, ActivityStatus.IN_PROGRESS);
    }
}
