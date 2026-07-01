package com.bipros.dbs.service.recompute;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages in-memory DBS recompute jobs. Jobs are stored in a {@link ConcurrentHashMap}
 * keyed by {@code jobId}; there is no DB persistence — jobs are ephemeral and lost on
 * server restart (acceptable per the in-memory decision).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbsRecomputeJobService {

    private final DbsRecomputeRunner runner;

    @PersistenceContext
    private EntityManager em;

    // package-private for testability (same-package test subclasses override resolveDays)
    final Map<UUID, DbsRecomputeJob> jobs = new ConcurrentHashMap<>();

    // Guards the check-then-put window: prevents two concurrent starts from both creating a job
    private final Set<UUID> inflight = ConcurrentHashMap.newKeySet();

    /**
     * Start a cumulative recompute from {@code MIN(report_date)} through today.
     * Re-entrant: if an active job exists for this project, returns it without
     * starting a second run.
     */
    public DbsRecomputeJob startCumulative(UUID projectId) {
        if (!inflight.add(projectId)) {
            return awaitConcurrentStart(projectId);
        }
        try {
            Optional<DbsRecomputeJob> existing = activeJobFor(projectId);
            if (existing.isPresent()) {
                log.info("Re-entrancy: returning existing active job {} for project {}", existing.get().getJobId(), projectId);
                return existing.get();
            }

            LocalDate from = resolveMinDate(projectId);
            LocalDate to = LocalDate.now();

            if (from == null) {
                // No DPRs and no existing DBS rows — create an immediately-succeeded job.
                DbsRecomputeJob job = new DbsRecomputeJob(projectId, RecomputeJobKind.CUMULATIVE, null, to, 0);
                job.setStatus(RecomputeJobStatus.SUCCEEDED);
                job.setStartedAt(Instant.now());
                job.setFinishedAt(Instant.now());
                jobs.put(job.getJobId(), job);
                log.info("DBS recompute cumulative: no DPRs/rows for project {}, job {} instantly SUCCEEDED", projectId, job.getJobId());
                return job;
            }

            List<LocalDate> days = resolveDays(projectId, from, to);
            DbsRecomputeJob job = new DbsRecomputeJob(projectId, RecomputeJobKind.CUMULATIVE, from, to, days.size());
            jobs.put(job.getJobId(), job);
            log.info("DBS recompute cumulative queued: job={} project={} from={} to={} days={}", job.getJobId(), projectId, from, to, days.size());
            runner.runAsync(job, projectId, days);
            return job;
        } finally {
            inflight.remove(projectId);
        }
    }

    /**
     * Start a range recompute for {@code [from, to]} (normalised so {@code from <= to}).
     * Re-entrant: if an active job exists for this project, returns it.
     */
    public DbsRecomputeJob startRange(UUID projectId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }

        if (!inflight.add(projectId)) {
            return awaitConcurrentStart(projectId);
        }
        try {
            Optional<DbsRecomputeJob> existing = activeJobFor(projectId);
            if (existing.isPresent()) {
                log.info("Re-entrancy: returning existing active job {} for project {}", existing.get().getJobId(), projectId);
                return existing.get();
            }

            List<LocalDate> days = resolveDays(projectId, from, to);
            DbsRecomputeJob job = new DbsRecomputeJob(projectId, RecomputeJobKind.RANGE, from, to, days.size());
            jobs.put(job.getJobId(), job);
            log.info("DBS recompute range queued: job={} project={} from={} to={} days={}", job.getJobId(), projectId, from, to, days.size());
            runner.runAsync(job, projectId, days);
            return job;
        } finally {
            inflight.remove(projectId);
        }
    }

    /** Look up a job by id. Returns empty when not found. */
    public Optional<DbsRecomputeJob> getJob(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Returns the most-recent active (QUEUED or RUNNING) job for a project,
     * or empty if none is active.
     */
    public Optional<DbsRecomputeJob> activeJobFor(UUID projectId) {
        return jobs.values().stream()
            .filter(j -> projectId.equals(j.getProjectId())
                && (j.getStatus() == RecomputeJobStatus.QUEUED || j.getStatus() == RecomputeJobStatus.RUNNING))
            .findFirst();
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * Loser of the {@link #inflight} race: the winning starter holds the slot but may not have
     * published its job to {@link #jobs} yet. Wait briefly for it to appear, then return it. The
     * winner always {@code jobs.put}s before releasing the slot, so once the slot is free a job for
     * this project is guaranteed present — the throw is a safety net that should never fire.
     */
    private DbsRecomputeJob awaitConcurrentStart(UUID projectId) {
        for (int i = 0; i < 200 && inflight.contains(projectId); i++) {
            if (activeJobFor(projectId).isPresent()) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return activeJobFor(projectId)
            .or(() -> jobs.values().stream()
                .filter(j -> projectId.equals(j.getProjectId()))
                .findFirst())
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent recompute start for project " + projectId));
    }

    /**
     * Union of approved-DPR dates ∪ existing dbs_daily_project dates in {@code [from, to]},
     * sorted ascending, de-duped. Package-private so test subclasses can override it.
     */
    @SuppressWarnings("unchecked")
    List<LocalDate> resolveDays(UUID projectId, LocalDate from, LocalDate to) {
        List<Object> raw = em.createNativeQuery(
                "SELECT d FROM (" +
                "  SELECT DISTINCT report_date AS d FROM project.daily_progress_reports" +
                "    WHERE project_id = cast(:pid as uuid) AND approval_status = 'APPROVED'" +
                "      AND report_date BETWEEN :from AND :to" +
                "  UNION" +
                "  SELECT DISTINCT report_date AS d FROM dbs.dbs_daily_project" +
                "    WHERE project_id = cast(:pid as uuid)" +
                "      AND report_date BETWEEN :from AND :to" +
                ") u ORDER BY d")
            .setParameter("pid", projectId.toString())
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList();

        return raw.stream()
            .map(r -> {
                if (r instanceof LocalDate ld) return ld;
                if (r instanceof Date d) return d.toLocalDate();
                // PostgreSQL may return java.sql.Date subtypes
                return Date.valueOf(r.toString()).toLocalDate();
            })
            .toList();
    }

    /**
     * Returns the earliest approved DPR date for the project, or {@code null} if no
     * approved DPRs exist. Package-private so test subclasses can override it.
     */
    LocalDate resolveMinDate(UUID projectId) {
        Object raw = em.createNativeQuery(
                "SELECT MIN(report_date) FROM project.daily_progress_reports" +
                " WHERE project_id = :pid AND approval_status = 'APPROVED'")
            .setParameter("pid", projectId)
            .getSingleResult();
        if (raw == null) return null;
        if (raw instanceof LocalDate ld) return ld;
        if (raw instanceof Date d) return d.toLocalDate();
        return Date.valueOf(raw.toString()).toLocalDate();
    }
}
