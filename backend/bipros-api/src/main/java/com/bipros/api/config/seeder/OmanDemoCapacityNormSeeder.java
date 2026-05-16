package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.OmanDemoWorkbookReader.CapacityRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads capacity / productivity-norm rows from {@code capacity-utilization.xlsx} and
 * reports a structured summary. Persistence into a project-scoped productivity-norm
 * entity is deferred for the same reason as {@link KhasabProductivityNormSeeder}: the
 * existing rate-master tables are keyed by (role_id, category_id, grade_id) and don't
 * carry a natural key for the workbook's (description, unit) pair — wiring requires a
 * cross-walk that does not yet exist.
 *
 * <p>The log entries this seeder emits are sufficient to confirm the workbook is loaded
 * and the data is available for the next mapping step; once the cross-walk is defined,
 * this seeder can be extended to persist {@code ProductivityNorm} rows scoped to the
 * project.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(204)
public class OmanDemoCapacityNormSeeder implements CommandLineRunner {

    private final OmanDemoWorkbookReader reader;

    @Override
    public void run(String... args) {
        if (!reader.capacityAvailable()) {
            log.info("[oman-demo capacity] capacity workbook not on classpath; skipping");
            return;
        }

        List<CapacityRow> rows;
        try {
            rows = reader.readCapacityRows();
        } catch (Exception e) {
            log.warn("[oman-demo capacity] failed to read capacity rows: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("[oman-demo capacity] workbook present but no capacity rows parsed");
            return;
        }

        Map<String, Long> byType = rows.stream()
                .collect(Collectors.groupingBy(CapacityRow::resourceType, Collectors.counting()));

        log.info("[oman-demo capacity] {} capacity rows parsed (equipment={}, manpower={})",
                rows.size(),
                byType.getOrDefault("EQUIPMENT", 0L),
                byType.getOrDefault("MANPOWER", 0L));

        // Sample a few utilisation outliers so the operator can spot data issues early.
        rows.stream()
                .filter(r -> r.utilizationPct() != null
                        && (r.utilizationPct().doubleValue() < 30.0
                            || r.utilizationPct().doubleValue() > 130.0))
                .limit(5)
                .forEach(r -> log.info("[oman-demo capacity] outlier {}/{}: util={}%, "
                                + "budgetedDays={}, actualDays={}",
                        r.resourceType(), r.description(), r.utilizationPct(),
                        r.budgetedDays(), r.actualDays()));

        log.info("[oman-demo capacity] persistence skipped — no cross-walk between "
                + "(description, unit) and rate-master keys yet (mirrors KhasabProductivityNormSeeder)");
    }
}
