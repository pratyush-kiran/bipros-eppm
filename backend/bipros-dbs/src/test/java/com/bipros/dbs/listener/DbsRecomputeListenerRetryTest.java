package com.bipros.dbs.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency regression: two DPRs committed for the same (project, date) make two
 * AFTER_COMMIT listener threads race on the shared DBS aggregate rows, and the loser
 * gets {@link ObjectOptimisticLockingFailureException} on the {@code version}-guarded
 * UPDATE. Because the recompute is idempotent, the listener must retry the losing
 * recompute (re-read the now-newer row, recompute, rewrite) rather than silently
 * leaving the row stale.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbsRecomputeListenerRetryTest {

    @Mock private DbsAggregationService aggregationService;
    @Mock private ProjectTeamService projectTeamService;
    @Mock private DailyProgressReportRepository dprRepository;

    @InjectMocks private DbsRecomputeListener listener;

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SUPERVISOR = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 1, 26);

    private DprSubmittedEvent event() {
        return DprSubmittedEvent.withoutChildren(
            PROJECT, UUID.randomUUID(), DATE, "Excavation",
            null, null, null, null, DprMutationType.CREATED, UUID.randomUUID());
    }

    private void stubProjectFanOut() {
        when(dprRepository.findDistinctSupervisorUserIdsByProjectAndDate(PROJECT, DATE))
            .thenReturn(List.of(SUPERVISOR));
        when(projectTeamService.resolveEngineerFor(PROJECT, SUPERVISOR)).thenReturn(Optional.empty());
        when(projectTeamService.resolveCmFor(PROJECT, SUPERVISOR)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a single optimistic-lock failure is retried and ultimately succeeds")
    void retriesThenSucceeds() {
        stubProjectFanOut();
        when(aggregationService.recomputeSupervisorDay(PROJECT, SUPERVISOR, DATE))
            .thenThrow(new ObjectOptimisticLockingFailureException(DbsDailySupervisor.class, SUPERVISOR))
            .thenReturn(null);

        assertThatCode(() -> listener.onDpr(event())).doesNotThrowAnyException();

        // First call lost the lock, second succeeded.
        verify(aggregationService, times(2)).recomputeSupervisorDay(PROJECT, SUPERVISOR, DATE);
    }

    @Test
    @DisplayName("a persistent optimistic-lock failure is retried a bounded number of times then swallowed")
    void exhaustsRetriesThenSwallows() {
        stubProjectFanOut();
        when(aggregationService.recomputeSupervisorDay(PROJECT, SUPERVISOR, DATE))
            .thenThrow(new ObjectOptimisticLockingFailureException(DbsDailySupervisor.class, SUPERVISOR));

        // Listener swallows after exhausting retries — parent commit must never be rolled back.
        assertThatCode(() -> listener.onDpr(event())).doesNotThrowAnyException();

        // 1 initial attempt + 4 retries.
        verify(aggregationService, times(5)).recomputeSupervisorDay(PROJECT, SUPERVISOR, DATE);
    }
}
