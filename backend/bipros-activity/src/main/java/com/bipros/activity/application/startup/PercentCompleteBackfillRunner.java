package com.bipros.activity.application.startup;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One-time backfill that recomputes percentComplete for all in-progress UNITS/DURATION
 * activities on application startup. Uses a distributed lease so only one pod in a
 * multi-instance deployment runs the backfill.
 *
 * <p>Idempotent — can safely be re-run on every boot. Each change is audited via
 * {@link AuditService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!test")
public class PercentCompleteBackfillRunner implements CommandLineRunner {

    private static final String JOB_NAME = "percent_complete_backfill";

    private final ActivityRepository activityRepository;
    private final ProjectRepository projectRepository;
    private final PercentCompleteCalculator calculator;
    private final AuditService auditService;
    private final ScheduledJobLeaseRepository leaseRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofMinutes(15));
        String owner = "node-" + UUID.randomUUID();
        if (leaseRepository.tryAcquire(JOB_NAME, until, now, owner) == 0) {
            log.info("PercentCompleteBackfillRunner skipped — another node holds the lease");
            return;
        }

        List<PercentCompleteType> types = List.of(PercentCompleteType.UNITS, PercentCompleteType.DURATION);
        List<ActivityStatus> statuses = List.of(ActivityStatus.IN_PROGRESS);

        List<Activity> activities = activityRepository.findAll().stream()
                .filter(a -> types.contains(a.getPercentCompleteType()) && statuses.contains(a.getStatus()))
                .toList();

        if (activities.isEmpty()) {
            log.info("PercentCompleteBackfillRunner: no in-progress UNITS/DURATION activities to backfill");
            return;
        }

        // IMPORTANT: do NOT default to LocalDate.now() when the project has no dataDate.
        // PercentCompleteBackfillRunner runs at every boot; if today is far past the activity's
        // actualStart + originalDuration, the calculator caps at 99.99 and clobbers a still-young
        // activity to "99.99% done" — which then becomes EV ≈ BAC in
        // ACTIVITY_PERCENT_COMPLETE EVM. Skip the activity instead so the (correct) DPR-driven
        // listener value (or the existing manual value) stays in place until an operator sets a
        // real project dataDate.
        Map<UUID, LocalDate> dataDateByProject = activities.stream()
                .map(Activity::getProjectId)
                .distinct()
                .filter(pid -> pid != null)
                .collect(java.util.HashMap::new,
                        (m, pid) -> {
                            LocalDate dd = projectRepository.findById(pid)
                                    .map(Project::getDataDate)
                                    .orElse(null);
                            if (dd != null) m.put(pid, dd);
                        },
                        java.util.HashMap::putAll);

        int updated = 0;
        int skippedNoDataDate = 0;
        for (Activity activity : activities) {
            LocalDate dataDate = dataDateByProject.get(activity.getProjectId());
            if (dataDate == null) {
                skippedNoDataDate++;
                continue;
            }
            Double oldPercent = activity.getPercentComplete();

            PercentCompleteCalculator.Result result = calculator.calculate(
                    activity, null, null, dataDate);

            if (result.isKeepPrior()) {
                continue;
            }

            activity.setPercentComplete(result.percent());
            if (activity.getPercentCompleteType() == PercentCompleteType.DURATION) {
                activity.setDurationPercentComplete(result.percent());
            } else {
                activity.setUnitsPercentComplete(result.percent());
            }
            if (result.status() != null) {
                activity.setStatus(result.status());
            }
            if (result.forcedActualFinish() != null) {
                activity.setActualFinishDate(result.forcedActualFinish());
            }

            activityRepository.save(activity);
            auditService.logUpdate("Activity", activity.getId(), "percentComplete",
                    oldPercent, result.percent());
            updated++;
        }

        log.info("PercentCompleteBackfillRunner updated {} activities (old values audited); skipped {} with no project dataDate",
                updated, skippedNoDataDate);
    }
}
