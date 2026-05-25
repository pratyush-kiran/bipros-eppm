package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls for queued ingestion jobs and runs them through the orchestrator.
 *
 * Note: {@code @EnableScheduling} is intentionally NOT declared here; the
 * top-level {@code BiprosApplication} in {@code bipros-api} already enables
 * scheduling for the whole reactor, and a second declaration is redundant
 * (and can cause duplicate-bean conflicts in some configurations).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IngestionWorker {

    private static final long ADVISORY_LOCK_KEY = 0x4844_5300_0000_0001L;  // "HDS\0...\1"

    private final HdsProperties props;
    private final HdsIngestionJobRepository jobRepo;
    private final IngestionOrchestrator orchestrator;
    private final JdbcTemplate jdbc;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final String workerId = "worker-" + System.getProperty("user.name") + "-" + Long.toHexString(System.nanoTime());

    @PostConstruct
    public void resetStaleJobsOnBoot() {
        Instant cutoff = Instant.now().minusSeconds(props.getIngestion().getStaleJobAfterSeconds());
        List<HdsIngestionJob> stale = jobRepo.findStaleJobs(cutoff);
        for (var j : stale) {
            log.warn("Resetting stale ingestion job {}: stage={}, last_heartbeat={}",
                j.getId(), j.getStage(), j.getLastHeartbeatAt());
            j.setLastHeartbeatAt(null);
            jobRepo.save(j);
        }
    }

    @Scheduled(fixedDelayString = "${bipros.hds.ingestion.worker-poll-seconds:5}000")
    public void poll() {
        if (busy.get()) return;
        Boolean got = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (got == null || !got) return;
        try {
            var maybeJob = jobRepo.findFirstByStageInOrderByCreatedAtAsc(List.of(
                HdsIngestionStage.PARSING, HdsIngestionStage.CHUNKING,
                HdsIngestionStage.EMBEDDING, HdsIngestionStage.INDEXING));
            if (maybeJob.isEmpty()) return;
            var job = maybeJob.get();
            busy.set(true);
            log.info("Picked up ingestion job {} (stage={}) on worker {}", job.getId(), job.getStage(), workerId);
            job.setWorkerId(workerId);
            job.setAttemptCount((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
            // Save and pass the FRESH entity (with bumped @Version) to the orchestrator
            // so subsequent saves inside run() don't trip optimistic-lock checks.
            var freshJob = jobRepo.save(job);
            try {
                orchestrator.run(freshJob);
            } catch (Exception e) {
                log.error("Job {} failed", freshJob.getId(), e);
            }
        } finally {
            jdbc.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
            busy.set(false);
        }
    }
}
