package com.bipros.api.config.seeder;

import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Backfills DBS daily rollup rows for DPRs that pre-date the {@code DprSubmittedEvent}
 * listener (Bug 4). On boot, walks each project's DPRs, finds every date that has at
 * least one DPR but no {@code dbs_daily_project} row, and calls
 * {@link DbsAggregationService#recomputeProjectDay(UUID, java.time.LocalDate)} for it.
 *
 * <p>Idempotent: dates that already have a {@code dbs_daily_project} row are skipped, so
 * re-running this seeder on an already-backfilled DB is a no-op. Gated by
 * {@code bipros.dbs.backfill.enabled} (default {@code true}) so prod can opt out.
 *
 * <p>Runs late ({@link Order} 200) — after the core data seeders, so any newly seeded
 * DPRs are also picked up. The bound is wide because DBS sits on top of the canonical
 * DPR / deployment / material tables, all of which must be present.
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class DbsBackfillSeeder implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DbsDailyProjectRepository dbsProjectRepository;
    private final DbsAggregationService aggregationService;
    private final PlatformTransactionManager transactionManager;

    @Value("${bipros.dbs.backfill.enabled:true}")
    private boolean enabled;

    /** Per-date isolation: a calculator failure on one date must not poison the next. */
    private TransactionTemplate perDateTx;

    @PostConstruct
    void initTx() {
        this.perDateTx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("[DbsBackfillSeeder] disabled via bipros.dbs.backfill.enabled=false — skipping");
            return;
        }

        List<Project> projects = projectRepository.findAll();
        if (projects.isEmpty()) {
            log.debug("[DbsBackfillSeeder] no projects, nothing to backfill");
            return;
        }

        log.info("[DbsBackfillSeeder] start: scanning {} projects for missing DBS rollups", projects.size());
        int projectsTouched = 0;
        int datesBackfilled = 0;
        int projectsAlreadyComplete = 0;

        for (Project project : projects) {
            UUID projectId = project.getId();
            List<DailyProgressReport> dprs = dprRepository
                .findByProjectIdOrderByReportDateAscIdAsc(projectId);
            if (dprs.isEmpty()) continue;

            // Distinct DPR dates for the project, in chronological order.
            TreeSet<LocalDate> dprDates = new TreeSet<>();
            for (DailyProgressReport d : dprs) {
                if (d.getReportDate() != null) dprDates.add(d.getReportDate());
            }
            if (dprDates.isEmpty()) continue;

            // Dates that already have a project rollup row.
            LocalDate min = dprDates.first();
            LocalDate max = dprDates.last();
            Set<LocalDate> existing = new TreeSet<>();
            dbsProjectRepository.findByProjectIdAndReportDateBetween(projectId, min, max)
                .forEach(r -> existing.add(r.getReportDate()));

            boolean touchedThisProject = false;
            for (LocalDate date : dprDates) {
                if (existing.contains(date)) continue;
                final UUID projId = projectId;
                final LocalDate d = date;
                try {
                    // Each date runs in its own transaction so a calculator/data drift
                    // failure on one date doesn't poison subsequent dates (e.g. legacy
                    // missing-table errors that PostgreSQL turns into 25P02 aborts).
                    perDateTx.executeWithoutResult(status -> {
                        // Mirror DbsController.recomputeProjectDay helper: run the
                        // supervisor recomputes first (which run all section
                        // calculators) before rolling up the project row. Bug 5/7 fix:
                        // skip the null-supervisor row when no real supervisors filed
                        // DPRs for the date.
                        List<UUID> supervisorIds = dprRepository
                            .findDistinctSupervisorUserIdsByProjectAndDate(projId, d);
                        for (UUID sup : supervisorIds) {
                            aggregationService.recomputeSupervisorDay(projId, sup, d);
                        }
                        aggregationService.recomputeProjectDay(projId, d);
                    });
                    datesBackfilled++;
                    touchedThisProject = true;
                } catch (Exception ex) {
                    log.warn("[DbsBackfillSeeder] recompute failed projectId={} date={}: {}",
                        projectId, date, ex.toString());
                }
            }

            if (touchedThisProject) {
                projectsTouched++;
                log.info("[DbsBackfillSeeder] backfilled projectId={} ({} dates)",
                    projectId, dprDates.size() - existing.size());
            } else {
                projectsAlreadyComplete++;
            }
        }

        log.info("[DbsBackfillSeeder] done: projectsTouched={} datesBackfilled={} projectsAlreadyComplete={}",
            projectsTouched, datesBackfilled, projectsAlreadyComplete);
    }
}
