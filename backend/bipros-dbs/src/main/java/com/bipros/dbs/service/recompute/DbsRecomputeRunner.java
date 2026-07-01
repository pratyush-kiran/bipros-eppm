package com.bipros.dbs.service.recompute;

import com.bipros.dbs.service.DbsAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Async runner for DBS recompute jobs. Kept as a separate {@code @Component} so the
 * {@code @Async} proxy boundary is real — a self-invoked method on
 * {@link DbsRecomputeJobService} would bypass the proxy and run synchronously.
 *
 * <p>The {@link #runAsync} method is dispatched on Spring's default async executor
 * (the orchestrator thread). Each per-day {@link CompletableFuture} is submitted to
 * the dedicated {@code dbsRecomputeDayExecutor} (size 4), capping real concurrency
 * well below the Hikari pool limit.
 */
@Slf4j
@Component
public class DbsRecomputeRunner {

    private final DbsAggregationService aggregationService;
    private final Executor dayExecutor;

    public DbsRecomputeRunner(
        DbsAggregationService aggregationService,
        @Qualifier("dbsRecomputeDayExecutor") Executor dayExecutor) {
        this.aggregationService = aggregationService;
        this.dayExecutor = dayExecutor;
    }

    /**
     * Runs on the default async executor. Submits one future per day to the bounded
     * {@code dbsRecomputeDayExecutor} then waits for all to complete.
     */
    @Async
    public void runAsync(DbsRecomputeJob job, UUID projectId, List<LocalDate> days) {
        job.setStatus(RecomputeJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        log.info("DBS recompute job {} started projectId={} days={}", job.getJobId(), projectId, days.size());

        try {
            List<CompletableFuture<Void>> futures = days.stream()
                .map(day -> CompletableFuture.runAsync(() -> {
                    try {
                        aggregationService.recomputeAllTiersForDay(projectId, day);
                    } finally {
                        job.getProcessedDays().incrementAndGet();
                    }
                }, dayExecutor))
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            job.setStatus(RecomputeJobStatus.SUCCEEDED);
            job.setFinishedAt(Instant.now());
            log.info("DBS recompute job {} SUCCEEDED projectId={} processedDays={}",
                job.getJobId(), projectId, job.getProcessedDays().get());

        } catch (Exception ex) {
            Throwable root = unwrapRoot(ex);
            String msg = root.getMessage();
            if (msg != null && msg.length() > 2000) {
                msg = msg.substring(0, 2000);
            }
            job.setErrorMessage(msg != null ? msg : root.getClass().getSimpleName());
            job.setStatus(RecomputeJobStatus.FAILED);
            job.setFinishedAt(Instant.now());
            log.error("DBS recompute job {} FAILED projectId={}: {}",
                job.getJobId(), projectId, root.toString());
        }
    }

    private static Throwable unwrapRoot(Throwable t) {
        Throwable cause;
        while ((cause = t.getCause()) != null && cause != t) t = cause;
        return t;
    }
}
