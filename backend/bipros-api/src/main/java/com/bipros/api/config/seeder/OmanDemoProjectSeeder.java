package com.bipros.api.config.seeder;

import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstraps the {@code OMAN-DEMO-KHASAB} project: a dedicated EPS root and the project
 * record itself, with metadata aligned to the real customer data (chainage, dates,
 * description). Runs at {@code @Order(201)} so the staff users created at
 * {@code @Order(200)} are already on disk (the project does not yet link to a user, but
 * the ordering keeps the seed log readable for operators).
 *
 * <p>Skip-if-exists: if {@link ProjectRepository#findByCode(String)} returns a row for
 * {@code OMAN-DEMO-KHASAB}, the seeder returns immediately so re-runs are cheap and
 * idempotent.
 *
 * <p>Profile-gated to {@code seed}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(201)
public class OmanDemoProjectSeeder implements CommandLineRunner {

    static final String PROJECT_CODE = "OMAN-DEMO-KHASAB";
    static final String PROJECT_NAME =
            "OMAN-Demo-Khasab — Khasab–Daba Asphalt Road & Link to Lima";
    static final String EPS_CODE = "OMAN-DEMO-EPS";
    static final String EPS_NAME = "OMAN Demo Programme";
    static final LocalDate PLANNED_START = LocalDate.of(2024, 10, 1);
    static final LocalDate PLANNED_FINISH = LocalDate.of(2026, 8, 31);
    static final LocalDate DATA_DATE = LocalDate.of(2026, 3, 31);
    static final long CHAINAGE_START_M = 0L;
    static final long CHAINAGE_END_M = 8_000L;

    private final ProjectRepository projectRepository;
    private final EpsNodeRepository epsNodeRepository;

    @Override
    public void run(String... args) {
        if (projectRepository.findByCode(PROJECT_CODE).isPresent()) {
            log.info("[oman-demo project] {} already seeded, skipping", PROJECT_CODE);
            return;
        }

        UUID epsId = ensureEpsNode();
        if (epsId == null) {
            log.error("[oman-demo project] could not provision EPS node {} — aborting",
                    EPS_CODE);
            return;
        }

        Project p = new Project();
        p.setCode(PROJECT_CODE);
        p.setName(PROJECT_NAME);
        p.setDescription(description());
        p.setEpsNodeId(epsId);
        p.setPlannedStartDate(PLANNED_START);
        p.setPlannedFinishDate(PLANNED_FINISH);
        p.setDataDate(DATA_DATE);
        p.setStatus(ProjectStatus.ACTIVE);
        p.setCategory("HIGHWAY");
        p.setIndustryCode("ROAD");
        p.setFromLocation("Khasab");
        p.setToLocation("Daba (with link to Lima)");
        p.setFromChainageM(CHAINAGE_START_M);
        p.setToChainageM(CHAINAGE_END_M);
        p.setTotalLengthKm(BigDecimal.valueOf(8.0));
        p.setPriority(50);
        p.setBudgetCurrency("OMR");

        try {
            Project saved = projectRepository.save(p);
            log.info("[oman-demo project] created project {} (id={}, eps={})",
                    saved.getCode(), saved.getId(), epsId);
        } catch (Exception e) {
            log.error("[oman-demo project] failed to save project: {}", e.getMessage(), e);
        }
    }

    private UUID ensureEpsNode() {
        Optional<EpsNode> existing = epsNodeRepository.findAll().stream()
                .filter(n -> EPS_CODE.equalsIgnoreCase(n.getCode()))
                .findFirst();
        if (existing.isPresent()) return existing.get().getId();

        EpsNode n = new EpsNode();
        n.setCode(EPS_CODE);
        n.setName(EPS_NAME);
        n.setSortOrder(0);
        try {
            return epsNodeRepository.save(n).getId();
        } catch (Exception e) {
            log.error("[oman-demo project] EPS node create failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String description() {
        return "Production-ready demo project. Sourced from real customer data: "
                + "Khasab daily progress (Jan–Mar 2025 — workbook headers say 2026 but cell "
                + "dates are 2025; we honour cells), SC180 historical performance snapshots "
                + "(Oct-24, Nov-24, Jan-25), DPR template, capacity utilization, and staff "
                + "master. Contract reference: SC-180 (Khasab–Daba Asphalt Road & Link to Lima).";
    }
}
