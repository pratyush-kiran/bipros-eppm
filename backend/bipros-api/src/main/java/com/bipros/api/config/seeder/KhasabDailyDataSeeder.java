package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ActivityCodeRow;
import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.DailyDataRawRow;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.model.EquipmentAvailability;
import com.bipros.project.domain.model.EquipmentOwnership;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.model.Side;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Khasab–Daba Asphalt Road Project (SC-180) — daily-operations seeder. Imports the real
 * customer DPR rows for Jan–Mar 2025 (workbook filenames say 2026 but the cell dates are 2025;
 * we honour the cells per the user's decision) from {@code seed-data/khasab/daily-data-khasab.xlsx}
 * via {@link KhasabDailyDataWorkbookReader}.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Find/create the SC-180 {@link Project}.</li>
 *   <li>Skip entirely if any DPR rows already exist for the project (idempotent re-runs).</li>
 *   <li>If the workbook isn't on the classpath, log and return — allowing the {@code seed}
 *       profile to boot without it.</li>
 *   <li>Ensure every BOQ activity from the workbook's {@code Code} sheet exists as an
 *       {@link Activity} on this project.</li>
 *   <li>Read all daily rows, group by (date, supervisor, activity, chainage), and emit one
 *       {@link DailyProgressReport} per group plus deduplicated manpower / equipment / material
 *       child rows.</li>
 * </ol>
 *
 * <p>Supervisor names are resolved to {@code users.id} via display-name match (case-insensitive),
 * populated by {@link KhasabSupervisorUserSeeder} ({@code @Order(179)}). Unresolved names leave
 * {@code supervisorUserId} null (allowed by the entity).
 *
 * <p>Profile-gated to {@code seed} only — never runs in prod.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(180)
public class KhasabDailyDataSeeder implements CommandLineRunner {

    private static final String PROJECT_CODE = "SC-180";
    private static final String PROJECT_NAME = "SC 180 — Khasab–Daba Asphalt Road & Link to Lima";
    private static final String CONTRACTOR_NAME = "Sandou Construction";
    private static final String DEFAULT_UNIT = "Nos";
    private static final String DEFAULT_WEATHER = "Clear";

    /** Skilled trade keywords (case-insensitive matched against the row's trade text). */
    private static final Set<String> SKILLED_TRADES = Set.of(
            "operator", "foreman", "supervisor", "mason", "carpenter",
            "steel fixer", "electrician", "plumber", "welder", "mechanic", "driver");
    private static final Set<String> SEMI_SKILLED_TRADES = Set.of(
            "chargehand", "helper / cleaner", "bankman", "rigger",
            "scaffolder", "painter", "survey helper");
    private static final Set<String> UNSKILLED_TRADES = Set.of(
            "helper", "watchman", "tyre man");

    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final DprEquipmentRepository equipmentRepository;
    private final DprMaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final EpsNodeRepository epsNodeRepository;
    private final KhasabDailyDataWorkbookReader reader;

    @Override
    public void run(String... args) {
        Project project = findOrCreateProject();
        if (project == null) {
            log.warn("[Khasab seeder] skipped: project create failed");
            return;
        }

        List<DailyProgressReport> existing =
                dprRepository.findByProjectIdOrderByReportDateAscIdAsc(project.getId());
        if (!existing.isEmpty()) {
            log.info("[Khasab seeder] skipped: {} DPRs already exist for {}",
                    existing.size(), project.getCode());
            return;
        }

        if (!reader.dailyDataAvailable()) {
            log.info("[Khasab seeder] daily-data workbook not present on classpath; skipping");
            return;
        }

        // ── 1. Activity code → activity id (and code → description for fallback display). ──
        Map<String, UUID> activityIdByCode = new HashMap<>();
        Map<String, String> nameByCode = new HashMap<>();
        Map<String, String> unitByCode = new HashMap<>();
        List<ActivityCodeRow> codes;
        try {
            codes = reader.readActivityCodes();
        } catch (Exception e) {
            log.warn("[Khasab seeder] failed to read activity codes: {}", e.getMessage());
            return;
        }
        ensureActivities(project.getId(), codes, activityIdByCode, nameByCode, unitByCode);

        // ── 2. Supervisor name → user id lookup. ──
        Map<String, UUID> supervisorIdByName = buildSupervisorIndex();

        // ── 3. Read and group raw rows. ──
        List<DailyDataRawRow> rows;
        try {
            rows = reader.readAllDailyRows();
        } catch (Exception e) {
            log.warn("[Khasab seeder] failed to read daily rows: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("[Khasab seeder] workbook present but no daily rows parsed");
            return;
        }

        // Preserve insertion order so chronological appearance matches the workbook.
        LinkedHashMap<GroupKey, List<DailyDataRawRow>> groups = new LinkedHashMap<>();
        for (DailyDataRawRow r : rows) {
            if (r.date() == null) continue;
            GroupKey key = new GroupKey(
                    r.date(),
                    nullToEmpty(r.supervisorName()),
                    nullToEmpty(r.activityCode()),
                    r.chainageFromM(),
                    r.chainageToM());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        int dprCount = 0;
        int mpCount = 0;
        int eqCount = 0;
        int matCount = 0;

        for (Map.Entry<GroupKey, List<DailyDataRawRow>> entry : groups.entrySet()) {
            GroupKey k = entry.getKey();
            List<DailyDataRawRow> grp = entry.getValue();

            DailyProgressReport dpr = buildDpr(
                    project.getId(), k, grp,
                    activityIdByCode, nameByCode, unitByCode,
                    supervisorIdByName);
            DailyProgressReport saved = dprRepository.save(dpr);
            dprCount++;

            // Dedup keys within the group so repeated lines collapse.
            LinkedHashMap<String, DprManpower> mpByKey = new LinkedHashMap<>();
            LinkedHashMap<String, DprEquipment> eqByKey = new LinkedHashMap<>();
            LinkedHashMap<String, DprMaterial> matByKey = new LinkedHashMap<>();

            for (DailyDataRawRow r : grp) {
                if (r.manpowerTrade() != null) {
                    String mpKey = r.manpowerTrade().toLowerCase(Locale.ROOT);
                    DprManpower existingMp = mpByKey.get(mpKey);
                    if (existingMp == null) {
                        mpByKey.put(mpKey, newManpower(saved.getId(), r));
                    } else {
                        mergeManpower(existingMp, r);
                    }
                }
                if (r.equipmentType() != null) {
                    String eqKey = r.equipmentType().toLowerCase(Locale.ROOT);
                    DprEquipment existingEq = eqByKey.get(eqKey);
                    if (existingEq == null) {
                        eqByKey.put(eqKey, newEquipment(saved.getId(), r));
                    } else {
                        mergeEquipment(existingEq, r);
                    }
                }
                if (r.materialDescription() != null && r.materialQty() != null) {
                    String matKey = r.materialDescription().toLowerCase(Locale.ROOT);
                    DprMaterial existingMat = matByKey.get(matKey);
                    if (existingMat == null) {
                        matByKey.put(matKey, newMaterial(saved.getId(), r));
                    } else {
                        mergeMaterial(existingMat, r);
                    }
                }
            }

            if (!mpByKey.isEmpty()) {
                manpowerRepository.saveAll(mpByKey.values());
                mpCount += mpByKey.size();
            }
            if (!eqByKey.isEmpty()) {
                equipmentRepository.saveAll(eqByKey.values());
                eqCount += eqByKey.size();
            }
            if (!matByKey.isEmpty()) {
                materialRepository.saveAll(matByKey.values());
                matCount += matByKey.size();
            }
        }

        log.info("[Khasab seeder] loaded {} DPRs, {} manpower, {} equipment, {} material from workbook",
                dprCount, mpCount, eqCount, matCount);
    }

    // ───────────────────────── Helpers ─────────────────────────

    private Project findOrCreateProject() {
        Optional<Project> match = projectRepository.findAll().stream()
                .filter(p -> p.getCode() != null
                        && (p.getCode().equalsIgnoreCase(PROJECT_CODE)
                            || p.getCode().equalsIgnoreCase("SC180")))
                .findFirst();
        if (match.isPresent()) {
            // Keep description in sync if the project was originally seeded with the
            // synthetic-data warning; otherwise leave the user's edits alone.
            Project found = match.get();
            String desc = found.getDescription();
            if (desc != null && desc.contains("SYNTHETIC")) {
                found.setDescription(realDataDescription());
                try {
                    return projectRepository.save(found);
                } catch (Exception e) {
                    log.warn("[Khasab seeder] could not refresh project description: {}", e.getMessage());
                    return found;
                }
            }
            return found;
        }

        Optional<Project> byName = projectRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().toLowerCase().contains("khasab"))
                .findFirst();
        if (byName.isPresent()) return byName.get();

        Project p = new Project();
        p.setCode(PROJECT_CODE);
        p.setName(PROJECT_NAME);
        p.setDescription(realDataDescription());
        p.setPlannedStartDate(LocalDate.of(2024, 11, 1));
        p.setPlannedFinishDate(LocalDate.of(2026, 6, 30));
        p.setDataDate(LocalDate.of(2025, 3, 31));
        p.setStatus(ProjectStatus.ACTIVE);
        p.setCategory("HIGHWAY");
        p.setFromLocation("Khasab");
        p.setToLocation("Daba");
        p.setFromChainageM(0L);
        p.setToChainageM(8000L);
        p.setTotalLengthKm(BigDecimal.valueOf(8.0));
        p.setEpsNodeId(ensureKhasabEpsNode());
        try {
            return projectRepository.save(p);
        } catch (Exception e) {
            log.error("[Khasab seeder] failed to create project: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns an EPS node id to slot the SC-180 project under. Prefers an existing Oman-related
     * node (OHA / OHA-MUS-BAT). Creates a lightweight "SC180-EPS" root if no candidate exists.
     */
    private UUID ensureKhasabEpsNode() {
        Optional<EpsNode> oha = epsNodeRepository.findAll().stream()
                .filter(n -> n.getCode() != null
                        && (n.getCode().equalsIgnoreCase("OHA")
                            || n.getCode().toUpperCase(Locale.ROOT).startsWith("OHA-MUS-BAT")))
                .findFirst();
        if (oha.isPresent()) return oha.get().getId();

        Optional<EpsNode> existingRoot = epsNodeRepository.findAll().stream()
                .filter(n -> "SC180-EPS".equalsIgnoreCase(n.getCode()))
                .findFirst();
        if (existingRoot.isPresent()) return existingRoot.get().getId();

        EpsNode node = new EpsNode();
        node.setCode("SC180-EPS");
        node.setName("SC-180 Khasab–Daba EPS");
        node.setSortOrder(0);
        try {
            return epsNodeRepository.save(node).getId();
        } catch (Exception e) {
            log.error("[Khasab seeder] failed to create EPS node: {}", e.getMessage());
            return null;
        }
    }

    private static String realDataDescription() {
        return "Design and Construction of Khasab–Daba Asphalt Road and Link to Lima. "
                + "Real customer DPR data for Jan–Mar 2025 imported from supplied workbook.";
    }

    private void ensureActivities(
            UUID projectId,
            List<ActivityCodeRow> codes,
            Map<String, UUID> activityIdByCode,
            Map<String, String> nameByCode,
            Map<String, String> unitByCode) {

        List<Activity> existing = activityRepository.findByProjectId(projectId);
        Map<String, Activity> existingByCode = new HashMap<>();
        for (Activity a : existing) {
            if (a.getCode() != null) existingByCode.put(a.getCode(), a);
        }

        for (ActivityCodeRow def : codes) {
            if (def.code() == null) continue;
            nameByCode.put(def.code(), def.description());
            if (def.unit() != null) unitByCode.put(def.code(), def.unit());

            Activity present = existingByCode.get(def.code());
            if (present != null) {
                activityIdByCode.put(def.code(), present.getId());
                continue;
            }
            Activity a = new Activity();
            a.setProjectId(projectId);
            a.setCode(def.code());
            a.setName(def.description() != null ? truncate(def.description(), 100) : def.code());
            a.setDescription(def.description());
            a.setStatus(ActivityStatus.IN_PROGRESS);
            try {
                Activity saved = activityRepository.save(a);
                activityIdByCode.put(def.code(), saved.getId());
            } catch (Exception e) {
                log.warn("[Khasab seeder] skipped activity {} ({}): {}",
                        def.code(), def.description(), e.getMessage());
            }
        }
    }

    private Map<String, UUID> buildSupervisorIndex() {
        Map<String, UUID> out = new HashMap<>();
        try {
            for (User u : userRepository.findAll()) {
                String display = displayName(u);
                if (display == null || display.isBlank()) continue;
                out.put(display.toLowerCase(Locale.ROOT), u.getId());
            }
        } catch (Exception e) {
            log.warn("[Khasab seeder] supervisor lookup failed: {}", e.getMessage());
        }
        return out;
    }

    private static String displayName(User u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        if (first == null && last == null) return u.getUsername();
        String composed = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return composed.isEmpty() ? u.getUsername() : composed;
    }

    /**
     * Resolves a supervisor display name from the workbook to a user id. Tries exact
     * (case-insensitive) match first, then a contains-match in either direction for robustness
     * against minor punctuation drift.
     */
    private UUID resolveSupervisorId(String supervisorName, Map<String, UUID> supervisorIdByName) {
        if (supervisorName == null || supervisorName.isBlank()) return null;
        String key = supervisorName.toLowerCase(Locale.ROOT).trim();
        UUID exact = supervisorIdByName.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, UUID> e : supervisorIdByName.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private DailyProgressReport buildDpr(
            UUID projectId,
            GroupKey k,
            List<DailyDataRawRow> grp,
            Map<String, UUID> activityIdByCode,
            Map<String, String> nameByCode,
            Map<String, String> unitByCode,
            Map<String, UUID> supervisorIdByName) {

        BigDecimal maxQty = BigDecimal.ZERO;
        String unit = null;
        Side side = null;
        for (DailyDataRawRow r : grp) {
            if (r.executedQty() != null && r.executedQty().compareTo(maxQty) > 0) {
                maxQty = r.executedQty();
            }
            if (unit == null && r.unit() != null) unit = r.unit();
            if (side == null) side = mapSide(r.site());
        }
        if (unit == null) unit = unitByCode.getOrDefault(k.activityCode(), DEFAULT_UNIT);
        if (unit == null || unit.isBlank()) unit = DEFAULT_UNIT;

        String activityName = nameByCode.get(k.activityCode());
        if (activityName == null || activityName.isBlank()) {
            activityName = k.activityCode().isEmpty() ? "Unspecified Activity" : k.activityCode();
        }
        activityName = truncate(activityName, 150);

        UUID activityId = activityIdByCode.get(k.activityCode());
        UUID supervisorUserId = resolveSupervisorId(k.supervisorName(), supervisorIdByName);

        return DailyProgressReport.builder()
                .projectId(projectId)
                .reportDate(k.date())
                .supervisorName(k.supervisorName().isEmpty() ? "Unspecified" : truncate(k.supervisorName(), 150))
                .supervisorUserId(supervisorUserId)
                .chainageFromM(k.chainageFromM())
                .chainageToM(k.chainageToM())
                .activityId(activityId)
                .activityName(activityName)
                .boqItemNo(truncate(k.activityCode(), 20))
                .unit(truncate(unit, 20))
                .qtyExecuted(maxQty)
                .weatherCondition(DEFAULT_WEATHER)
                .side(side != null ? side : Side.LHS)
                .shift(Shift.DAY)
                .approvalStatus(DprApprovalStatus.APPROVED)
                .contractorName(CONTRACTOR_NAME)
                .safetyIncidentType(SafetyIncidentType.NONE)
                .build();
    }

    private DprManpower newManpower(UUID dprId, DailyDataRawRow r) {
        return DprManpower.builder()
                .dprId(dprId)
                .trade(truncate(r.manpowerTrade(), 100))
                .category(categorise(r.manpowerTrade()))
                .nos(r.manpowerNos() != null ? r.manpowerNos() : 1)
                .workingHours(nz(r.manpowerHours()))
                .otHours(BigDecimal.ZERO)
                .idleHours(BigDecimal.ZERO)
                .unitRate(r.manpowerRate())
                .unitRateBasis("HOUR")
                .lineCost(r.manpowerCost())
                .contractorName(CONTRACTOR_NAME)
                .build();
    }

    private void mergeManpower(DprManpower acc, DailyDataRawRow r) {
        if (r.manpowerNos() != null) acc.setNos((acc.getNos() == null ? 0 : acc.getNos()) + r.manpowerNos());
        acc.setWorkingHours(sum(acc.getWorkingHours(), r.manpowerHours()));
        acc.setLineCost(sum(acc.getLineCost(), r.manpowerCost()));
        // Keep the existing unitRate; per-line rate is informational once costs are summed.
    }

    private DprEquipment newEquipment(UUID dprId, DailyDataRawRow r) {
        return DprEquipment.builder()
                .dprId(dprId)
                .equipmentType(truncate(r.equipmentType(), 100))
                .ownership(EquipmentOwnership.OWNED)
                .nos(r.equipmentNos() != null ? r.equipmentNos() : 1)
                .workingHours(nz(r.equipmentHours()))
                .idleHours(BigDecimal.ZERO)
                .breakdownHours(BigDecimal.ZERO)
                .availabilityStatus(EquipmentAvailability.UTILIZED)
                .unitRate(r.equipmentRate())
                .unitRateBasis("HOUR")
                .lineCost(r.equipmentCost())
                .build();
    }

    private void mergeEquipment(DprEquipment acc, DailyDataRawRow r) {
        if (r.equipmentNos() != null) acc.setNos((acc.getNos() == null ? 0 : acc.getNos()) + r.equipmentNos());
        acc.setWorkingHours(sum(acc.getWorkingHours(), r.equipmentHours()));
        acc.setLineCost(sum(acc.getLineCost(), r.equipmentCost()));
    }

    private DprMaterial newMaterial(UUID dprId, DailyDataRawRow r) {
        return DprMaterial.builder()
                .dprId(dprId)
                .materialName(truncate(r.materialDescription(), 150))
                .quantity(r.materialQty())
                .unit(truncate(r.materialUnit(), 20))
                .unitRate(r.materialRate())
                .lineCost(r.materialCost())
                .build();
    }

    private void mergeMaterial(DprMaterial acc, DailyDataRawRow r) {
        acc.setQuantity(sum(acc.getQuantity(), r.materialQty()));
        acc.setLineCost(sum(acc.getLineCost(), r.materialCost()));
    }

    private static ManpowerCategory categorise(String trade) {
        if (trade == null) return ManpowerCategory.SKILLED;
        String t = trade.toLowerCase(Locale.ROOT).trim();
        // Match longest/most-specific first: "helper / cleaner" must be detected before "helper".
        if (SEMI_SKILLED_TRADES.contains(t)) return ManpowerCategory.SEMI_SKILLED;
        if (SKILLED_TRADES.contains(t)) return ManpowerCategory.SKILLED;
        if (UNSKILLED_TRADES.contains(t)) return ManpowerCategory.UNSKILLED;
        // Fall back to substring match for fuzzy hits.
        for (String s : SEMI_SKILLED_TRADES) if (t.contains(s)) return ManpowerCategory.SEMI_SKILLED;
        for (String s : UNSKILLED_TRADES) if (t.contains(s)) return ManpowerCategory.UNSKILLED;
        for (String s : SKILLED_TRADES) if (t.contains(s)) return ManpowerCategory.SKILLED;
        return ManpowerCategory.SKILLED;
    }

    private static Side mapSide(String site) {
        if (site == null) return null;
        String s = site.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "LHS", "LEFT" -> Side.LHS;
            case "RHS", "RIGHT" -> Side.RHS;
            case "CENTER", "CENTRE", "MEDIAN", "BOTH" -> Side.CENTER;
            default -> null;
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Composite grouping key for raw rows. {@code activityCode} and {@code supervisorName} use "" for null. */
    private record GroupKey(
            LocalDate date,
            String supervisorName,
            String activityCode,
            Long chainageFromM,
            Long chainageToM) {}
}
