package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.ResourceAssignmentActualsRolledUpEvent;
import com.bipros.common.util.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;

/**
 * Reacts to {@link ResourceAssignmentActualsRolledUpEvent} (published AFTER the cost rollup
 * commits) and updates the activity's percentComplete for UNITS-typed activities.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityUnitsPercentCompleteListener {

    private final ActivityRepository activityRepository;
    private final PercentCompleteCalculator calculator;
    private final AuditService auditService;
    private final com.bipros.activity.application.percent.BoqProgressGuard boqProgressGuard;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onResourceAssignmentActualsRolledUp(ResourceAssignmentActualsRolledUpEvent event) {
        Activity activity = activityRepository.findById(event.activityId()).orElse(null);
        if (activity == null) {
            log.debug("Activity {} not found — units percent complete skipped", event.activityId());
            return;
        }
        if (activity.getPercentCompleteType() != PercentCompleteType.UNITS) {
            return;
        }
        if (boqProgressGuard.isBoqDriven(activity.getId())) {
            return; // BOQ workdone owns percentComplete (precedence #1)
        }

        Double oldPercent = activity.getPercentComplete();
        var oldStatus = activity.getStatus();

        PercentCompleteCalculator.Result result = calculator.calculate(
                activity, event.plannedUnitsSum(), event.actualUnitsSum(), LocalDate.now());

        if (result.isKeepPrior()) {
            return;
        }

        activity.setUnitsPercentComplete(result.percent());
        activity.setPercentComplete(result.percent());
        if (result.status() != null && result.status() != activity.getStatus()) {
            activity.setStatus(result.status());
        }
        if (result.forcedActualFinish() != null) {
            activity.setActualFinishDate(result.forcedActualFinish());
        }

        activityRepository.save(activity);
        log.info("UNITS percent rollup: activity={} {}% -> {}%",
                activity.getId(), oldPercent, result.percent());

        if (!java.util.Objects.equals(oldPercent, result.percent())) {
            auditService.logUpdate("Activity", activity.getId(), "percentComplete",
                    oldPercent, result.percent());
        }
        if (!java.util.Objects.equals(oldStatus, activity.getStatus())) {
            auditService.logUpdate("Activity", activity.getId(), "status",
                    oldStatus, activity.getStatus());
        }
    }
}
