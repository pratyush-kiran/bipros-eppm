package com.bipros.dbs.listener;

import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.event.MaterialConsumptionLoggedEvent;
import com.bipros.common.event.ResourceDeploymentSavedEvent;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AFTER_COMMIT listener that recomputes the DBS rollups after a relevant source event:
 *
 * <ul>
 *   <li>{@link DprSubmittedEvent} — recompute the supervisor's day, then their engineer,
 *       then the project rollup.</li>
 *   <li>{@link ResourceDeploymentSavedEvent} — DRD has no supervisor FK; we recompute
 *       every supervisor's day that has any DPR for that (project, date), plus the
 *       project rollup.</li>
 *   <li>{@link MaterialConsumptionLoggedEvent} — same fan-out as deployment.</li>
 * </ul>
 *
 * Failures are swallowed and logged at WARN so DBS recompute never breaks the parent
 * transaction. AFTER_COMMIT phase guarantees the parent has already committed by the
 * time we run, so an exception here cannot rollback caller work.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsRecomputeListener {

    private final DbsAggregationService aggregationService;
    private final ProjectTeamService projectTeamService;
    private final DailyProgressReportRepository dprRepository;

    /**
     * Bug 7 diagnostic: log at INFO on bean construction so the next QA run can confirm
     * the listener is actually wired into the Spring context. If this log line is missing
     * from startup, bipros-dbs is not on the classpath / scan path of bipros-api and the
     * event chain cannot fire regardless of how many publishers exist upstream.
     */
    @PostConstruct
    void logBindings() {
        log.info("DbsRecomputeListener bound: DprSubmittedEvent, ResourceDeploymentSavedEvent, "
            + "MaterialConsumptionLoggedEvent (phase=AFTER_COMMIT)");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDpr(DprSubmittedEvent e) {
        // Bug 7 diagnostic: confirm the event actually arrives at the listener. If this
        // log line is absent after a DPR POST, the publisher / transactional boundary is
        // the problem; if it's present but DBS still empty, recomputeChain is failing
        // (see WARN with stack trace below).
        log.info("DbsRecomputeListener.onDpr received projectId={} dprId={} supervisor={} date={} type={}",
            e.projectId(), e.dprId(), e.supervisorUserId(), e.reportDate(), e.eventType());
        // Fan out to every supervisor with a DPR on (project, date) — not just the
        // submitting one. When the submitting DPR changes BOQ qty_executed_to_date,
        // the cumulative figures on OTHER supervisors' rows go stale. Project-wide
        // recompute keeps the engineer/CM/PM rollups consistent. Same pattern used
        // by onDeployment / onMaterial below.
        recomputeProjectForDate(e.projectId(), e.reportDate());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeployment(ResourceDeploymentSavedEvent e) {
        log.debug("DbsRecomputeListener.onDeployment received projectId={} date={}",
            e.projectId(), e.logDate());
        recomputeProjectForDate(e.projectId(), e.logDate());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMaterial(MaterialConsumptionLoggedEvent e) {
        log.debug("DbsRecomputeListener.onMaterial received projectId={} date={}",
            e.projectId(), e.logDate());
        recomputeProjectForDate(e.projectId(), e.logDate());
    }

    private void recomputeChain(UUID projectId, UUID supervisorUserId, LocalDate date) {
        try {
            aggregationService.recomputeSupervisorDay(projectId, supervisorUserId, date);
            if (supervisorUserId != null) {
                UUID engineerUserId = projectTeamService
                    .resolveEngineerFor(projectId, supervisorUserId)
                    .orElse(null);
                if (engineerUserId != null) {
                    aggregationService.recomputeEngineerDay(projectId, engineerUserId, date);
                }
                // Phase 4: roll up to the CM tier when this supervisor reports through a CM.
                UUID cmUserId = projectTeamService
                    .resolveCmFor(projectId, supervisorUserId)
                    .orElse(null);
                if (cmUserId != null) {
                    aggregationService.recomputeCmDay(projectId, cmUserId, date);
                }
            }
            aggregationService.recomputeProjectDay(projectId, date);
            log.info("DBS recompute OK projectId={} supervisor={} date={}",
                projectId, supervisorUserId, date);
        } catch (Exception ex) {
            // Bug 7 diagnostic: emit the stack trace, not just toString(). The prior
            // single-line log hid the root cause and made silent failures effectively
            // un-debuggable. Listener still swallows so the parent commit isn't rolled back.
            log.warn("DBS recompute failed projectId={} supervisor={} date={}",
                projectId, supervisorUserId, date, ex);
        }
    }

    private void recomputeProjectForDate(UUID projectId, LocalDate date) {
        try {
            List<UUID> supervisorIds = new ArrayList<>(
                dprRepository.findDistinctSupervisorUserIdsByProjectAndDate(projectId, date));
            if (supervisorIds.isEmpty()) {
                supervisorIds.add(null);
            }
            for (UUID sup : supervisorIds) {
                recomputeChain(projectId, sup, date);
            }
            aggregationService.recomputeProjectDay(projectId, date);
        } catch (Exception ex) {
            log.warn("DBS recompute (project fan-out) failed projectId={} date={}",
                projectId, date, ex);
        }
    }
}
