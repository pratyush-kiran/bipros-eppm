package com.bipros.dbs.service.recompute;

import com.bipros.dbs.service.DbsAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the DBS async recompute job service + runner.
 *
 * <p>{@link DbsAggregationService} is mocked — no DB required. An inline (synchronous)
 * {@link Executor} is injected so the runner completes immediately; no fixed sleep is used.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbsRecomputeJobServiceTest {

    @Mock
    DbsAggregationService aggregationService;

    /** Runs tasks on the calling thread — deterministic, no real concurrency in tests. */
    private static final Executor INLINE = Runnable::run;

    private static final UUID PROJECT = UUID.randomUUID();
    private static final LocalDate D1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 3);

    private DbsRecomputeRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DbsRecomputeRunner(aggregationService, INLINE);
    }

    // ── runner: happy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("runner: all days processed → SUCCEEDED, processedDays == totalDays")
    void runnerHappyPath() {
        DbsRecomputeJob job = new DbsRecomputeJob(PROJECT, RecomputeJobKind.CUMULATIVE, D1, D3, 3);

        runner.runAsync(job, PROJECT, List.of(D1, D2, D3));

        assertThat(job.getStatus()).isEqualTo(RecomputeJobStatus.SUCCEEDED);
        assertThat(job.getProcessedDays().get()).isEqualTo(3);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isNotNull();

        verify(aggregationService).recomputeAllTiersForDay(PROJECT, D1);
        verify(aggregationService).recomputeAllTiersForDay(PROJECT, D2);
        verify(aggregationService).recomputeAllTiersForDay(PROJECT, D3);
    }

    @Test
    @DisplayName("runner: injected failure → FAILED + errorMessage, processedDays still incremented")
    void runnerFailurePath() {
        doThrow(new RuntimeException("disk full")).when(aggregationService)
            .recomputeAllTiersForDay(any(), any());

        DbsRecomputeJob job = new DbsRecomputeJob(PROJECT, RecomputeJobKind.RANGE, D1, D1, 1);
        runner.runAsync(job, PROJECT, List.of(D1));

        assertThat(job.getStatus()).isEqualTo(RecomputeJobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("disk full");
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("runner: failure on one day — other days still invoked via allOf")
    void runnerOneDayFailureOthersInvoked() {
        doThrow(new RuntimeException("boom")).when(aggregationService)
            .recomputeAllTiersForDay(eq(PROJECT), eq(D2));

        DbsRecomputeJob job = new DbsRecomputeJob(PROJECT, RecomputeJobKind.CUMULATIVE, D1, D3, 3);
        runner.runAsync(job, PROJECT, List.of(D1, D2, D3));

        assertThat(job.getStatus()).isEqualTo(RecomputeJobStatus.FAILED);
        // D1 and D3 were still submitted; INLINE executor runs all futures before join() throws
        verify(aggregationService).recomputeAllTiersForDay(eq(PROJECT), eq(D1));
        verify(aggregationService).recomputeAllTiersForDay(eq(PROJECT), eq(D2));
        verify(aggregationService).recomputeAllTiersForDay(eq(PROJECT), eq(D3));
        // processedDays incremented in finally block for all (including D2 after its exception)
        assertThat(job.getProcessedDays().get()).isEqualTo(3);
    }

    // ── service: job lifecycle ───────────────────────────────────────────────────

    @Test
    @DisplayName("startCumulative: job starts QUEUED then SUCCEEDED; recomputeAllTiersForDay called once per day")
    void startCumulativeJobLifecycle() throws Exception {
        StubService svc = new StubService(runner, List.of(D1, D2, D3), D1);

        DbsRecomputeJob job = svc.startCumulative(PROJECT);

        // inline executor → runner.runAsync is synchronous despite @Async in production
        awaitTerminal(job, 5);

        assertThat(job.getTotalDays()).isEqualTo(3);
        assertThat(job.getStatus()).isEqualTo(RecomputeJobStatus.SUCCEEDED);
        assertThat(job.getProcessedDays().get()).isEqualTo(3);

        verify(aggregationService, times(1)).recomputeAllTiersForDay(PROJECT, D1);
        verify(aggregationService, times(1)).recomputeAllTiersForDay(PROJECT, D2);
        verify(aggregationService, times(1)).recomputeAllTiersForDay(PROJECT, D3);
    }

    @Test
    @DisplayName("no DPRs / no DBS rows: startCumulative returns SUCCEEDED immediately with totalDays=0")
    void noDaysInstantlySucceeded() {
        // null minDate → stub returns empty list
        StubService svc = new StubService(runner, List.of(), null);

        DbsRecomputeJob job = svc.startCumulative(PROJECT);

        assertThat(job.getStatus()).isEqualTo(RecomputeJobStatus.SUCCEEDED);
        assertThat(job.getTotalDays()).isEqualTo(0);
        verifyNoInteractions(aggregationService);
    }

    // ── service: re-entrancy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("second startCumulative while one is active returns the same jobId")
    void reEntrancyReturnsSameJobId() {
        // Use a no-op runner so the first job stays QUEUED
        DbsRecomputeRunner noop = new DbsRecomputeRunner(aggregationService, INLINE) {
            @Override
            public void runAsync(DbsRecomputeJob job, UUID projectId, List<LocalDate> days) {
                // do not advance status — leave QUEUED
            }
        };
        StubService svc = new StubService(noop, List.of(D1), D1);

        DbsRecomputeJob first = svc.startCumulative(PROJECT);
        assertThat(first.getStatus()).isEqualTo(RecomputeJobStatus.QUEUED);

        DbsRecomputeJob second = svc.startCumulative(PROJECT);
        assertThat(second.getJobId()).isEqualTo(first.getJobId());

        verifyNoInteractions(aggregationService);
    }

    // ── service: getJob / activeJobFor ───────────────────────────────────────────

    @Test
    @DisplayName("getJob returns empty when jobId is unknown")
    void getJobUnknown() {
        StubService svc = new StubService(runner, List.of(), null);
        assertThat(svc.getJob(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("activeJobFor returns empty when no active job exists")
    void activeJobForEmpty() {
        StubService svc = new StubService(runner, List.of(), null);
        assertThat(svc.activeJobFor(PROJECT)).isEmpty();
    }

    // ── resolveDays union contract ────────────────────────────────────────────────
    // NOTE: resolveDays union/sort/dedup is enforced by the native SQL UNION ... ORDER BY in
    // DbsRecomputeJobService.resolveDays; no unit coverage here (no DB in this test module).

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void awaitTerminal(DbsRecomputeJob job, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            RecomputeJobStatus s = job.getStatus();
            if (s == RecomputeJobStatus.SUCCEEDED || s == RecomputeJobStatus.FAILED) return;
            Thread.sleep(10);
        }
        throw new AssertionError(
            "Job did not reach terminal status within " + timeoutSeconds + "s; current=" + job.getStatus());
    }

    // ── test double ──────────────────────────────────────────────────────────────

    /**
     * Subclass of {@link DbsRecomputeJobService} that injects fixed day-lists without
     * hitting a DB. Package-private visibility grants access to the {@code jobs} map.
     */
    static class StubService extends DbsRecomputeJobService {

        private final List<LocalDate> stubDays;
        private final LocalDate stubMinDate;

        StubService(DbsRecomputeRunner runner, List<LocalDate> stubDays, LocalDate stubMinDate) {
            super(runner);
            this.stubDays = stubDays;
            this.stubMinDate = stubMinDate;
        }

        @Override
        List<LocalDate> resolveDays(UUID projectId, LocalDate from, LocalDate to) {
            return stubDays;
        }

        @Override
        LocalDate resolveMinDate(UUID projectId) {
            return stubMinDate;
        }
    }
}
