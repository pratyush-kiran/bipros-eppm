package com.bipros.dbs.service.recompute;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory holder for a single DBS recompute job. NOT a JPA entity.
 * Fields use {@code volatile} + {@link AtomicInteger} so the async runner's
 * updates are visible across threads without explicit locking.
 */
public class DbsRecomputeJob {

    private final UUID jobId;
    private final UUID projectId;
    private final RecomputeJobKind kind;
    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final int totalDays;
    private final AtomicInteger processedDays = new AtomicInteger(0);

    private volatile RecomputeJobStatus status;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String errorMessage;

    public DbsRecomputeJob(UUID projectId, RecomputeJobKind kind,
                           LocalDate fromDate, LocalDate toDate, int totalDays) {
        this.jobId = UUID.randomUUID();
        this.projectId = projectId;
        this.kind = kind;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.totalDays = totalDays;
        this.status = RecomputeJobStatus.QUEUED;
    }

    public UUID getJobId() { return jobId; }
    public UUID getProjectId() { return projectId; }
    public RecomputeJobKind getKind() { return kind; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public int getTotalDays() { return totalDays; }
    public AtomicInteger getProcessedDays() { return processedDays; }

    public RecomputeJobStatus getStatus() { return status; }
    public void setStatus(RecomputeJobStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
