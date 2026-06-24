package com.bipros.activity.application.scheduling;

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
import org.springframework.scheduling.annotation.Scheduled;
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
 * Nightly sweep that recomputes {@code percentComplete} for every in-progress
 * DURATION-typed activity from elapsed days and the project's data date.
 * Uses a multi-instance lease so only one node fires the job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DurationPercentCompleteJob {

    private static final String JOB_NAME = "duration_percent_complete";

    private final ActivityRepository activityRepository;
    private final ProjectRepository projectRepository;
    private final PercentCompleteCalculator calculator;
    private final AuditService auditService;
    private final ScheduledJobLeaseRepository leaseRepository;
    private final com.bipros.activity.application.percent.BoqProgressGuard boqProgressGuard;

    @Scheduled(cron = "${bipros.activity.duration-percent-cron:0 5 2 * * *}")
    @Transactional
    public void run() {
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofMinutes(10));
        String owner = "node-" + UUID.randomUUID();
        if (leaseRepository.tryAcquire(JOB_NAME, until, now, owner) == 0) {
            log.debug("DurationPercentCompleteJob skipped — another node holds the lease");
            return;
        }

        List<Activity> activities = activityRepository.findByPercentCompleteTypeAndStatusIn(
                PercentCompleteType.DURATION, List.of(ActivityStatus.IN_PROGRESS));

        if (activities.isEmpty()) {
            log.debug("No in-progress DURATION activities to process");
            return;
        }

        // Group by project to batch-load data dates. Skip projects with a null dataDate — we
        // must NOT default to LocalDate.now() here because actualStart + originalDuration may
        // be deep in the past relative to today, which causes the calculator to cap percent
        // complete at 99.99 and clobber a still-young activity (then EV ≈ BAC in
        // ACTIVITY_PERCENT_COMPLETE EVM).
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
            if (boqProgressGuard.isBoqDriven(activity.getId())) {
                continue; // BOQ-driven — its percentComplete is owned by the BOQ listener
            }
            Double oldPercent = activity.getPercentComplete();

            PercentCompleteCalculator.Result result = calculator.calculate(activity, null, null, dataDate);
            if (result.isKeepPrior()) {
                continue;
            }

            activity.setDurationPercentComplete(result.percent());
            activity.setPercentComplete(result.percent());
            if (result.status() != null) {
                activity.setStatus(result.status());
            }
            if (result.forcedActualFinish() != null) {
                activity.setActualFinishDate(result.forcedActualFinish());
            }

            activityRepository.save(activity);
            if (!java.util.Objects.equals(oldPercent, result.percent())) {
                auditService.logUpdate("Activity", activity.getId(), "percentComplete",
                        oldPercent, result.percent());
            }
            updated++;
        }

        if (updated > 0 || skippedNoDataDate > 0) {
            log.info("DurationPercentCompleteJob updated {} activities; skipped {} with no project dataDate",
                    updated, skippedNoDataDate);
        }
    }
}
