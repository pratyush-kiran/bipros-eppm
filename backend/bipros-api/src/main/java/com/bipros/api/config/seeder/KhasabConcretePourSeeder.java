package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ConcretePourRow;
import com.bipros.project.domain.model.ConcretePour;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ConcretePourRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeds the {@code project.concrete_pour} table with the customer's real Khasab + Lima pour
 * records (Dec 2025 – Apr 2026, ~1,231 rows). Runs after {@link KhasabDailyDataSeeder} (@Order 180)
 * so the SC-180 project is guaranteed to exist.
 *
 * <p>Profile-gated to {@code seed}. Idempotent — skips when any ConcretePour row already exists
 * for the project.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(182)
public class KhasabConcretePourSeeder implements CommandLineRunner {

    private static final String PROJECT_CODE = "SC-180";

    private final ProjectRepository projectRepository;
    private final ConcretePourRepository concretePourRepository;
    private final KhasabDailyDataWorkbookReader reader;

    @Override
    public void run(String... args) {
        if (!reader.concreteAvailable()) {
            log.info("[Khasab concrete] no concrete workbook on classpath — skipping");
            return;
        }

        Project project = findProject();
        if (project == null) {
            log.warn("[Khasab concrete] SC-180 project not found — skipping");
            return;
        }

        if (concretePourRepository.existsByProjectId(project.getId())) {
            log.info("[Khasab concrete] pours already exist for {}, skipping", project.getCode());
            return;
        }

        List<ConcretePourRow> rows = reader.readConcretePours();
        if (rows.isEmpty()) {
            log.warn("[Khasab concrete] workbook returned 0 rows");
            return;
        }

        List<ConcretePour> entities = new ArrayList<>(rows.size());
        int khasab = 0, lima = 0;
        for (ConcretePourRow r : rows) {
            ConcretePour cp = ConcretePour.builder()
                    .projectId(project.getId())
                    .pourDate(r.pourDate())
                    .site(truncate(r.site(), 50))
                    .plantName(truncate(r.plantName(), 100))
                    .chainageM(r.chainageM())
                    .structure(truncate(r.structure(), 150))
                    .element(truncate(r.element(), 255))
                    .gradeCode(truncate(r.gradeCode(), 20))
                    .quantityM3(r.quantityM3())
                    .slumpValue(r.slump())
                    .temperatureC(r.temperature())
                    .sectionLabel(sanitiseSection(r.section()))
                    .build();
            entities.add(cp);
            if ("Khasab".equalsIgnoreCase(r.site())) khasab++;
            else if ("Lima".equalsIgnoreCase(r.site())) lima++;
        }

        concretePourRepository.saveAll(entities);
        log.info("[Khasab concrete] loaded {} pours ({} Khasab + {} Lima) for project {}",
                entities.size(), khasab, lima, project.getCode());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Drops Excel-formula leftover strings (start with "IF(", "=", "#REF!") and truncates to 150. */
    private static String sanitiseSection(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("IF(") || trimmed.startsWith("=") || trimmed.startsWith("#REF!")) {
            return null;
        }
        return trimmed.length() <= 150 ? trimmed : trimmed.substring(0, 150);
    }

    private Project findProject() {
        Optional<Project> match = projectRepository.findAll().stream()
                .filter(p -> p.getCode() != null
                        && (p.getCode().equalsIgnoreCase(PROJECT_CODE)
                            || p.getCode().equalsIgnoreCase("SC180")))
                .findFirst();
        if (match.isPresent()) return match.get();
        return projectRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().toLowerCase().contains("khasab"))
                .findFirst()
                .orElse(null);
    }
}
