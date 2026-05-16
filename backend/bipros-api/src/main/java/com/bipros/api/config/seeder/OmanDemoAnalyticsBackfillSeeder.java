package com.bipros.api.config.seeder;

import com.bipros.analytics.etl.backfill.AnalyticsBackfillService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Belt-and-suspenders ClickHouse population step for {@code OMAN-DEMO-KHASAB}.
 *
 * <p>During the seed, the existing {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * listeners ({@code DprSubmittedListener}, {@code ActivityExpenseRecordedListener}, …)
 * already mirror Postgres writes into ClickHouse in real time. This seeder runs after
 * every domain seeder has finished so it can:
 *
 * <ol>
 *   <li>Call {@link AnalyticsBackfillService#backfillAll(LocalDate, LocalDate, UUID)}
 *       for the project's date window — fills any gaps the listeners missed and ensures
 *       {@code dim_*} tables are synced.</li>
 *   <li>Probe per-fact-table row counts via
 *       {@link AnalyticsBackfillService#countClickHouseRows(String)} and log them, so the
 *       seed log proves the data made it across.</li>
 * </ol>
 *
 * <p>Never throws: if backfill fails (e.g. ClickHouse is offline in a dev environment),
 * the seeder logs an error and lets boot complete. The Postgres data is independently
 * usable — the AI assistant's Postgres-backed tools still answer most questions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(210)
public class OmanDemoAnalyticsBackfillSeeder implements CommandLineRunner {

    private static final LocalDate BACKFILL_FROM = LocalDate.of(2024, 9, 1);
    private static final LocalDate BACKFILL_TO = LocalDate.of(2026, 4, 30);
    private static final List<String> FACT_TABLES_TO_PROBE = List.of(
            "fact_dpr_logs",
            "fact_activity_progress_daily",
            "fact_cost_daily",
            "fact_evm_daily",
            "fact_risk_snapshot_daily"
    );

    private final ProjectRepository projectRepository;
    private final AnalyticsBackfillService backfillService;

    @Override
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo backfill] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        UUID projectId = projectOpt.get().getId();

        try {
            AnalyticsBackfillService.BackfillReport report =
                    backfillService.backfillAll(BACKFILL_FROM, BACKFILL_TO, projectId);
            log.info("[oman-demo backfill] backfillAll done for {}: "
                            + "dpr={}, activity={}, cost={}, evm={}, risk={}",
                    OmanDemoProjectSeeder.PROJECT_CODE,
                    report.dprInserted(), report.activityInserted(),
                    report.costInserted(), report.evmProjectsProcessed(),
                    report.riskInserted());
        } catch (Exception e) {
            log.error("[oman-demo backfill] backfillAll FAILED — ClickHouse may be offline or "
                    + "unreachable. Postgres data is still usable; AI assistant Postgres-backed "
                    + "tools will continue to work. Error: {}", e.getMessage());
            return;
        }

        for (String table : FACT_TABLES_TO_PROBE) {
            try {
                long count = backfillService.countClickHouseRows(table);
                if (count == 0) {
                    log.warn("[oman-demo backfill] ClickHouse table {} has 0 rows after backfill",
                            table);
                } else {
                    log.info("[oman-demo backfill] ClickHouse {} row count: {}", table, count);
                }
            } catch (Exception e) {
                log.warn("[oman-demo backfill] could not query ClickHouse {}: {}",
                        table, e.getMessage());
            }
        }
    }
}
