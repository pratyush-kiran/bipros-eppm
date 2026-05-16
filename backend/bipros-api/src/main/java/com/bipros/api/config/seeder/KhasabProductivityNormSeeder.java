package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ProductivityNormRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads productivity norms from the customer's SC-180 performance workbook and reports the
 * count. The {@code ManpowerRateMaster} / {@code EquipmentRateMaster} entities are keyed by
 * (role_id, category_id, grade_id) and do not carry a (boqCode, resourceCode) natural key —
 * mapping the workbook's textual resource codes ("HLP", "EXV", ...) to the role-only rate
 * tables requires an explicit cross-walk that does not yet exist. Rather than fabricating
 * arbitrary FKs, this seeder logs the loaded norm count so the data is visibly available
 * for the next mapping step. A future seeder can pick up persistence once the cross-walk is
 * defined.
 *
 * <p>Profile-gated to {@code seed} only — never runs in prod.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(181)
public class KhasabProductivityNormSeeder implements CommandLineRunner {

    private final KhasabDailyDataWorkbookReader reader;

    @Override
    public void run(String... args) {
        if (!reader.performanceAvailable()) {
            log.info("[Khasab productivity] performance workbook not present on classpath; skipping");
            return;
        }

        List<ProductivityNormRow> norms;
        try {
            norms = reader.readProductivityNorms();
        } catch (Exception e) {
            log.warn("[Khasab productivity] failed to read norms: {}", e.getMessage());
            return;
        }

        if (norms.isEmpty()) {
            log.info("[Khasab productivity] workbook present but no norm rows parsed");
            return;
        }

        // The rate-master entities (resource.manpower_rate_masters / resource.equipment_rate_masters)
        // are keyed by role_id / category_id / grade_id, NOT by (boqCode, resourceCode). Persisting
        // requires a cross-walk that does not exist yet — leaving a clear seam instead of inventing
        // FKs. The next iteration can wire this up.
        log.info("[Khasab productivity] {} norms loaded but no rate-master entity found; skipping persistence",
                norms.size());
    }
}
