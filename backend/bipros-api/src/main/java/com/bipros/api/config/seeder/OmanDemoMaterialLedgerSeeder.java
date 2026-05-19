package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.util.SeederResourceFactory;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.domain.model.MaterialIssue;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceMaterialDetails;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.MaterialIssueRepository;
import com.bipros.resource.domain.repository.ResourceMaterialDetailsRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Seeds the {@link MaterialIssue} + {@link MaterialConsumptionLog} ledgers for
 * {@code OMAN-DEMO-KHASAB} so the Material KPI block on the Insights tab lights
 * up. Without these rows, {@link com.bipros.api.service.MaterialKpiService}
 * short-circuits with all zeros and the UI renders the "No material issues or
 * consumption logs in this window" banner.
 *
 * <p>For each {@code OMD-MAT-*} master Resource it generates one weekly issue
 * from 90 days ago through today (~13 issues per material), each followed by
 * one or two consumption logs over the next 1–6 days. The wastage band is
 * 1–4 % (target ≤ 3 % per NH48 KPI 8.3) and utilisation lands ~92–99 %.
 *
 * <p>Idempotent: skips if any issue already exists for the project in the last
 * 120 days. To rebuild, delete the corresponding {@code material_issue} +
 * {@code material_consumption_logs} rows for the project and re-boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(211)
public class OmanDemoMaterialLedgerSeeder implements CommandLineRunner {

    private static final int LOOKBACK_DAYS = 90;
    private static final int WEEKLY_STEP_DAYS = 7;
    private static final DateTimeFormatter CHALLAN_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final ProjectRepository projectRepository;
    private final ResourceRepository resourceRepository;
    private final ResourceMaterialDetailsRepository materialDetailsRepository;
    private final MaterialIssueRepository issueRepository;
    private final MaterialConsumptionLogRepository consumptionLogRepository;
    private final SeederResourceFactory resourceFactory;

    /** Realistic road-construction material starter set if the workbook didn't seed any. */
    private static final List<DefaultMaterial> DEFAULT_MATERIALS = List.of(
            new DefaultMaterial("OMD-MAT-CEMENT-OPC-43",        "Cement OPC 43 Grade",     "Bag",   3.2),
            new DefaultMaterial("OMD-MAT-STEEL-FE500",          "Steel Reinforcement Fe500","MT",   620.0),
            new DefaultMaterial("OMD-MAT-AGGREGATE-20MM",       "Aggregate 20mm",          "m3",    14.5),
            new DefaultMaterial("OMD-MAT-AGGREGATE-10MM",       "Aggregate 10mm",          "m3",    15.0),
            new DefaultMaterial("OMD-MAT-SAND",                 "Sand (Manufactured)",     "m3",    12.0),
            new DefaultMaterial("OMD-MAT-BITUMEN-VG30",         "Bitumen VG30",            "MT",   320.0),
            new DefaultMaterial("OMD-MAT-GSB",                  "GSB Material",            "m3",    11.0),
            new DefaultMaterial("OMD-MAT-WMM",                  "WMM Material",            "m3",    13.5),
            new DefaultMaterial("OMD-MAT-DBM",                  "DBM Mix",                 "MT",   240.0),
            new DefaultMaterial("OMD-MAT-BC",                   "BC Mix",                  "MT",   260.0));

    private record DefaultMaterial(String code, String name, String unit, double ratePerUnit) {}

    @Override
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo material-ledger] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        Project project = projectOpt.get();
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(LOOKBACK_DAYS);

        List<MaterialIssue> existing = issueRepository
                .findByProjectIdAndIssueDateBetween(project.getId(), windowStart, today);
        if (!existing.isEmpty()) {
            log.info("[oman-demo material-ledger] {} issues already exist for {} in last "
                            + "{} days, skipping",
                    existing.size(), project.getCode(), LOOKBACK_DAYS);
            return;
        }

        List<Resource> materials = new ArrayList<>();
        for (Resource r : resourceRepository.findAll()) {
            if (r.getCode() != null && r.getCode().startsWith("OMD-MAT-")) {
                materials.add(r);
            }
        }
        if (materials.isEmpty()) {
            log.info("[oman-demo material-ledger] no OMD-MAT-* resources found — "
                    + "creating the default road-material starter set ({} items)",
                    DEFAULT_MATERIALS.size());
            materials = ensureDefaultMaterials();
            if (materials.isEmpty()) {
                log.warn("[oman-demo material-ledger] failed to create default materials, skipping");
                return;
            }
        }

        int issuesWritten = 0;
        int consumptionWritten = 0;
        int matIdx = 0;
        for (Resource mat : materials) {
            int issueIdxForMaterial = 0;
            BigDecimal weeklyIssueQty = weeklyIssueQtyFor(mat);
            BigDecimal unitRate = mat.getCostPerUnit() != null && mat.getCostPerUnit().signum() > 0
                    ? mat.getCostPerUnit()
                    : new BigDecimal("1.00").setScale(4, RoundingMode.HALF_UP);
            String unit = mat.getUnit() != null && !mat.getUnit().isBlank() ? mat.getUnit() : "Each";

            for (LocalDate d = windowStart; !d.isAfter(today); d = d.plusDays(WEEKLY_STEP_DAYS)) {
                // Wastage 1–4 % derived from the (material index, issue index) tuple.
                double wastageFrac = 0.01d + ((matIdx * 7 + issueIdxForMaterial) % 30) / 1000d;
                BigDecimal wastage = weeklyIssueQty.multiply(BigDecimal.valueOf(wastageFrac))
                        .setScale(3, RoundingMode.HALF_UP);

                MaterialIssue issue = MaterialIssue.builder()
                        .projectId(project.getId())
                        .challanNumber(buildChallanNumber(d, matIdx, issueIdxForMaterial))
                        .materialId(mat.getId())
                        .issueDate(d)
                        .quantity(weeklyIssueQty)
                        .wastageQuantity(wastage)
                        .remarks("Seeded for Oman-Demo Insights demo")
                        .build();
                try {
                    issueRepository.save(issue);
                    issuesWritten++;
                } catch (Exception e) {
                    log.warn("[oman-demo material-ledger] issue save failed for {} on {}: {}",
                            mat.getCode(), d, e.getMessage());
                    continue;
                }

                // Two consumption log rows per issue → utilisation lands in 92–99 % band.
                int logsPerIssue = 2;
                BigDecimal remaining = weeklyIssueQty.subtract(wastage);
                BigDecimal perLog = remaining.divide(BigDecimal.valueOf(logsPerIssue), 3, RoundingMode.HALF_UP);
                BigDecimal opening = weeklyIssueQty;
                for (int k = 0; k < logsPerIssue; k++) {
                    LocalDate logDate = d.plusDays(k + 1);
                    if (logDate.isAfter(today)) break;
                    BigDecimal consumed = perLog;
                    BigDecimal closing = opening.subtract(consumed);
                    MaterialConsumptionLog logRow = MaterialConsumptionLog.builder()
                            .projectId(project.getId())
                            .logDate(logDate)
                            .resourceId(mat.getId())
                            .materialName(truncate(mat.getName(), 150))
                            .unit(unit)
                            .openingStock(opening)
                            .received(BigDecimal.ZERO)
                            .consumed(consumed)
                            .closingStock(closing)
                            .wastagePercent(BigDecimal.valueOf(wastageFrac * 100).setScale(2, RoundingMode.HALF_UP))
                            .unitRate(unitRate)
                            .lineCost(consumed.multiply(unitRate).setScale(2, RoundingMode.HALF_UP))
                            .remarks("Seeded daily consumption")
                            .build();
                    try {
                        consumptionLogRepository.save(logRow);
                        consumptionWritten++;
                    } catch (Exception e) {
                        log.warn("[oman-demo material-ledger] log save failed for {} on {}: {}",
                                mat.getCode(), logDate, e.getMessage());
                    }
                    opening = closing;
                }
                issueIdxForMaterial++;
            }
            matIdx++;
        }

        log.info("[oman-demo material-ledger] wrote {} material issues, {} consumption logs "
                        + "across {} materials over {} day window",
                issuesWritten, consumptionWritten, materials.size(), LOOKBACK_DAYS);
    }

    private static String buildChallanNumber(LocalDate date, int matIdx, int seq) {
        // ISS-YYYYMM-NNNN — globally unique by combining material index + week.
        int n = (matIdx * 1000 + seq) % 10_000;
        return String.format("ISS-OMD-%s-%04d", date.format(CHALLAN_MONTH), n);
    }

    /** Reasonable weekly issued quantity per material kind. */
    private static BigDecimal weeklyIssueQtyFor(Resource mat) {
        String name = mat.getName() == null ? "" : mat.getName().toLowerCase();
        double qty;
        if (name.contains("cement")) qty = 220.0;
        else if (name.contains("steel") || name.contains("reinforce")) qty = 18.0;
        else if (name.contains("aggregate") || name.contains("gsb") || name.contains("wmm")) qty = 480.0;
        else if (name.contains("bitumen") || name.contains("emulsion")) qty = 12.0;
        else if (name.contains("sand")) qty = 320.0;
        else if (name.contains("brick") || name.contains("block")) qty = 4500.0;
        else qty = 80.0;
        return BigDecimal.valueOf(qty).setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Creates the fallback set of OMD-MAT-* Resource rows when the Oman workbook
     * didn't bring any of its own. The role used is the catch-all
     * {@code IMPORTED-MATERIAL} role that {@link SeederResourceFactory} bootstraps.
     */
    private List<Resource> ensureDefaultMaterials() {
        ResourceType type;
        ResourceRole role;
        try {
            type = resourceFactory.requireType("MATERIAL");
            role = resourceFactory.ensureRole("IMPORTED-MATERIAL", "MATERIAL");
        } catch (Exception e) {
            log.warn("[oman-demo material-ledger] cannot bootstrap MATERIAL type/role: {}",
                    e.getMessage());
            return List.of();
        }
        List<Resource> created = new ArrayList<>();
        for (DefaultMaterial dm : DEFAULT_MATERIALS) {
            Optional<Resource> existing = resourceRepository.findByCode(dm.code());
            if (existing.isPresent()) {
                created.add(existing.get());
                continue;
            }
            Resource r = new Resource();
            r.setCode(dm.code());
            r.setName(dm.name());
            r.setResourceType(type);
            r.setRole(role);
            r.setUnit(dm.unit());
            r.setStatus(ResourceStatus.ACTIVE);
            r.setSortOrder(0);
            r.setCostPerUnit(BigDecimal.valueOf(dm.ratePerUnit()).setScale(4, RoundingMode.HALF_UP));
            try {
                Resource saved = resourceRepository.save(r);
                materialDetailsRepository.save(ResourceMaterialDetails.builder()
                        .resourceId(saved.getId())
                        .baseUnit(dm.unit())
                        .build());
                created.add(saved);
            } catch (Exception e) {
                log.warn("[oman-demo material-ledger] resource save failed for {}: {}",
                        dm.code(), e.getMessage());
            }
        }
        return created;
    }
}
