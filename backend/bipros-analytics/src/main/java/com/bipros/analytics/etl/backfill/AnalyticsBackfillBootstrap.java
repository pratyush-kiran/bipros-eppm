package com.bipros.analytics.etl.backfill;

import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!test")
@ConditionalOnProperty(name = "analytics.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsBackfillBootstrap {

    private static final String JOB_NAME = "analytics_first_boot_backfill";
    private static final Map<String, String> FACT_TABLES = Map.of(
            "dpr", "fact_dpr_logs",
            "activity", "fact_activity_progress_daily",
            "cost", "fact_cost_daily",
            "evm", "fact_evm_daily",
            "risk", "fact_risk_snapshot_daily"
    );

    private final ScheduledJobLeaseRepository leaseRepository;
    private final AnalyticsBackfillService backfillService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofMinutes(15));
        String owner = "node-" + UUID.randomUUID();
        if (leaseRepository.tryAcquire(JOB_NAME, until, now, owner) == 0) {
            log.debug("AnalyticsBackfillBootstrap skipped — another node holds the lease");
            return;
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(2);

        for (Map.Entry<String, String> entry : FACT_TABLES.entrySet()) {
            String fact = entry.getKey();
            String table = entry.getValue();
            try {
                long count = backfillService.countClickHouseRows(table);
                if (count == 0) {
                    log.info("Backfill bootstrap: {} is empty, running backfill for last 2 years", table);
                    switch (fact) {
                        case "dpr" -> backfillService.backfillDpr(from, to, null);
                        case "activity" -> backfillService.backfillActivityProgress(from, to, null);
                        case "cost" -> backfillService.backfillCost(from, to, null);
                        case "evm" -> backfillService.backfillEvm(from, to, null);
                        case "risk" -> backfillService.backfillRiskSnapshot(from, to, null);
                    }
                    log.info("Backfill bootstrap: {} complete", table);
                } else {
                    log.debug("Backfill bootstrap: {} already has {} rows, skipping", table, count);
                }
            } catch (Exception e) {
                log.warn("Backfill bootstrap failed for {}: {}", table, e.getMessage(), e);
            }
        }

        log.info("AnalyticsBackfillBootstrap finished");
    }
}
