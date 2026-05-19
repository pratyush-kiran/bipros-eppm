package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.api.config.seeder.OmanDemoWorkbookReader.DailyDataRawRow;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.model.EquipmentAvailability;
import com.bipros.project.domain.model.EquipmentOwnership;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.model.Side;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The headline importer for {@code OMAN-DEMO-KHASAB}: reads the Khasab daily-data
 * workbook, groups raw rows into {@link DailyProgressReport}s, deduplicates manpower /
 * equipment / material child rows, and writes them in row-by-row batches so the
 * AFTER_COMMIT analytics listeners fire incrementally and Postgres → ClickHouse stays in
 * sync during the seed.
 *
 * <p>Auto-fix during import — never drops a row when a soft fix is possible:
 * <ul>
 *   <li>Unresolved supervisor name → activity's owning supervisor (set in the WBS seeder).</li>
 *   <li>Unknown activity code → DPR is saved with a free-text {@code activityName} and a
 *       null {@code activityId}; subsequent rollups still work because activity_name is
 *       the rollup key in {@code DailyProgressReportRepository.sumQtyExecutedThroughDate}.</li>
 *   <li>Negative or NaN quantity → coerced to zero with a {@code WARN}, row preserved.</li>
 * </ul>
 *
 * <p>Idempotent: if any DPR exists for {@code OMAN-DEMO-KHASAB} we skip — the existing
 * Khasab seeder uses the same guard, so the demo project can be re-seeded by deleting
 * its DPRs explicitly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
// 208 = run AFTER OmanDemoActivityResourceSeeder (207). That seeder creates the
// OMD-LAB-* / OMD-EQ-* / OMD-MAT-* master Resource rows; this one then links
// every DPR child row to the right Resource so the KPI services can roll them
// up. Used to be 206 (before resources existed) — that left resourceId null
// and every Manpower/Equipment KPI computed against zero rows.
@Order(208)
public class OmanDemoDailyDataSeeder implements CommandLineRunner {

    private static final String CONTRACTOR_NAME = "Sandou Construction";
    private static final String DEFAULT_UNIT = "Nos";
    private static final String DEFAULT_WEATHER = "Clear";

    /**
     * Cell dates in the customer workbook are 2025, but the workbook filename and demo
     * intent say "Jan, Feb, Mar 2026". We honour the filename so the demo lands data in
     * the system's "now" window, and the default DPR UI filter opens on populated rows
     * rather than an empty range.
     */
    private static final int YEAR_SHIFT = 1;

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
    private final ResourceRepository resourceRepository;
    private final OmanDemoWorkbookReader reader;
    private final OmanDemoStaffDirectory directory;

    // Built once at the top of run() — maps a workbook trade / equipment-type string
    // (lower-cased, trimmed) to the OMD-LAB-* / OMD-EQ-* master Resource UUID created
    // by OmanDemoActivityResourceSeeder (@Order 207).
    private Map<String, UUID> manpowerResourceByName = new HashMap<>();
    private Map<String, UUID> equipmentResourceByName = new HashMap<>();

    @Override
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo daily] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        Project project = projectOpt.get();

        // Build the Resource lookup maps before we start writing DPR child rows so
        // every DprManpower / DprEquipment row gets its resourceId populated and
        // ManpowerKpiService / EquipmentKpiService can roll them up.
        loadResourceLookups();

        // Back-fill resourceId on existing DPR child rows that were written
        // before this seeder learned how to wire them up. One-shot, no-ops on
        // the next boot once every row has a non-null resourceId.
        backfillResourceIdsOnExistingDprRows(project.getId());

        // Idempotency: skip the workbook import only if DPRs in the workbook's actual
        // date range are already present. After YEAR_SHIFT the range is 2026-01-24
        // onward (cells say 2025; we land them in 2026 to match filename + demo
        // "now"). Historical performance snapshots (Oct, Nov, Jan-5 — also +1y to
        // Oct-25/Nov-25/Jan-26) share the project but live in a different date
        // range, so they must not block the real daily-data import. The synthetic
        // roll-forward at the bottom of this method has its own idempotency guard.
        List<DailyProgressReport> dailyWindow =
                dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
                        project.getId(),
                        LocalDate.of(2025 + YEAR_SHIFT, 1, 24),
                        LocalDate.of(2025 + YEAR_SHIFT, 4, 30));
        boolean workbookAlreadyImported = !dailyWindow.isEmpty();
        if (workbookAlreadyImported) {
            log.info("[oman-demo daily] {} workbook DPRs already exist for {} — "
                            + "skipping workbook import, running synthetic roll-forward only",
                    dailyWindow.size(), project.getCode());
            rollForwardSyntheticDprs(project.getId());
            return;
        }

        if (!reader.dailyDataAvailable()) {
            log.info("[oman-demo daily] daily-data workbook not on classpath; skipping");
            return;
        }

        // Index activities by code so daily-data rows resolve to FK + name in O(1).
        Map<String, Activity> activityByCode = new HashMap<>();
        for (Activity a : activityRepository.findByProjectId(project.getId())) {
            if (a.getCode() != null) activityByCode.put(a.getCode().trim(), a);
        }
        if (activityByCode.isEmpty()) {
            log.warn("[oman-demo daily] no activities for {}, skipping",
                    project.getCode());
            return;
        }

        List<DailyDataRawRow> rows;
        try {
            rows = reader.readAllDailyRows();
        } catch (Exception e) {
            log.warn("[oman-demo daily] failed to read daily rows: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("[oman-demo daily] workbook present but no daily rows parsed");
            return;
        }

        // Preserve insertion order so dashboards render chronologically.
        LinkedHashMap<GroupKey, java.util.List<DailyDataRawRow>> groups = new LinkedHashMap<>();
        int negativeQty = 0;
        for (DailyDataRawRow r : rows) {
            if (r.date() == null) continue;
            DailyDataRawRow fixed = r;
            if (r.executedQty() != null
                    && r.executedQty().compareTo(BigDecimal.ZERO) < 0) {
                negativeQty++;
                fixed = withZeroQty(r);
            }
            // Shift cell date by YEAR_SHIFT (see field doc): 2025-01-24 → 2026-01-24, etc.
            LocalDate shifted = fixed.date().plusYears(YEAR_SHIFT);
            GroupKey key = new GroupKey(
                    shifted,
                    nullToEmpty(fixed.supervisorName()),
                    nullToEmpty(fixed.activityCode()),
                    fixed.chainageFromM(),
                    fixed.chainageToM());
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(fixed);
        }
        if (negativeQty > 0) {
            log.warn("[oman-demo daily] coerced {} negative quantities to zero", negativeQty);
        }

        int dprCount = 0;
        int mpCount = 0;
        int eqCount = 0;
        int matCount = 0;
        int unresolvedSup = 0;
        int unknownActivity = 0;

        int batchInGroup = 0;
        for (Map.Entry<GroupKey, java.util.List<DailyDataRawRow>> entry : groups.entrySet()) {
            GroupKey k = entry.getKey();
            java.util.List<DailyDataRawRow> grp = entry.getValue();

            Activity activity = activityByCode.get(k.activityCode());
            UUID activityId = activity == null ? null : activity.getId();
            UUID wbsId = activity == null ? null : activity.getWbsNodeId();
            if (activity == null && !k.activityCode().isEmpty()) {
                unknownActivity++;
            }

            UUID supervisorUserId = directory.resolve(k.supervisorName());
            if (supervisorUserId == null && activity != null) {
                supervisorUserId = activity.getSupervisorUserId();
            }
            if (supervisorUserId == null) {
                unresolvedSup++;
            }
            String supervisorDisplay = k.supervisorName().isEmpty()
                    ? "Unspecified" : k.supervisorName();

            DailyProgressReport dpr;
            try {
                dpr = buildDpr(project.getId(), k, grp, activity, activityId, wbsId,
                        supervisorUserId, supervisorDisplay);
            } catch (Exception e) {
                log.warn("[oman-demo daily] skipping group {}/{}/{} due to build error: {}",
                        k.date(), k.supervisorName(), k.activityCode(), e.getMessage());
                continue;
            }
            DailyProgressReport saved;
            try {
                saved = dprRepository.save(dpr);
            } catch (Exception e) {
                log.warn("[oman-demo daily] DPR save failed at {}/{}/{}: {}",
                        k.date(), k.supervisorName(), k.activityCode(), e.getMessage());
                continue;
            }
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
                try {
                    manpowerRepository.saveAll(mpByKey.values());
                    mpCount += mpByKey.size();
                } catch (Exception e) {
                    log.warn("[oman-demo daily] manpower save failed for dpr {}: {}",
                            saved.getId(), e.getMessage());
                }
            }
            if (!eqByKey.isEmpty()) {
                try {
                    equipmentRepository.saveAll(eqByKey.values());
                    eqCount += eqByKey.size();
                } catch (Exception e) {
                    log.warn("[oman-demo daily] equipment save failed for dpr {}: {}",
                            saved.getId(), e.getMessage());
                }
            }
            if (!matByKey.isEmpty()) {
                try {
                    materialRepository.saveAll(matByKey.values());
                    matCount += matByKey.size();
                } catch (Exception e) {
                    log.warn("[oman-demo daily] material save failed for dpr {}: {}",
                            saved.getId(), e.getMessage());
                }
            }

            // Drive the activity status / progress forward so dashboards reflect activity.
            if (activity != null && !ActivityStatus.IN_PROGRESS.equals(activity.getStatus())) {
                try {
                    activity.setStatus(ActivityStatus.IN_PROGRESS);
                    if (activity.getActualStartDate() == null) {
                        activity.setActualStartDate(k.date());
                    }
                    activityRepository.save(activity);
                } catch (Exception ignored) {
                    // Cosmetic update — never block the seed for it.
                }
            }

            batchInGroup++;
            if (batchInGroup % 200 == 0) {
                log.info("[oman-demo daily] progress: {} DPRs saved", dprCount);
            }
        }

        log.info("[oman-demo daily] loaded {} DPRs ({} manpower, {} equipment, {} material) "
                        + "from workbook (unresolved supervisors={}, unknown activity codes={})",
                dprCount, mpCount, eqCount, matCount, unresolvedSup, unknownActivity);

        // After the real workbook import, extend the DPR stream forward so the
        // default 30-day Insights window includes today (R1 in the design plan).
        rollForwardSyntheticDprs(project.getId());
    }

    // ---------- Resource lookup helpers ----------

    /**
     * Snapshot all OMD-LAB-* / OMD-EQ-* Resources into name → id maps so
     * {@link #resolveManpowerResourceId(String)} and
     * {@link #resolveEquipmentResourceId(String)} are O(1) per DPR row.
     * Keys are the display-name lower-cased — matches what the workbook hands us
     * via {@code r.manpowerTrade()} / {@code r.equipmentType()}.
     */
    private void loadResourceLookups() {
        manpowerResourceByName = new HashMap<>();
        equipmentResourceByName = new HashMap<>();
        int mp = 0, eq = 0;
        for (Resource r : resourceRepository.findAll()) {
            if (r.getCode() == null || r.getName() == null) continue;
            if (r.getCode().startsWith("OMD-LAB-")) {
                manpowerResourceByName.put(r.getName().trim().toLowerCase(Locale.ROOT), r.getId());
                mp++;
            } else if (r.getCode().startsWith("OMD-EQ-")) {
                equipmentResourceByName.put(r.getName().trim().toLowerCase(Locale.ROOT), r.getId());
                eq++;
            }
        }
        log.info("[oman-demo daily] resource lookup snapshot: {} manpower, {} equipment", mp, eq);
    }

    private UUID resolveManpowerResourceId(String trade) {
        if (trade == null) return null;
        return manpowerResourceByName.get(trade.trim().toLowerCase(Locale.ROOT));
    }

    private UUID resolveEquipmentResourceId(String equipmentType) {
        if (equipmentType == null) return null;
        return equipmentResourceByName.get(equipmentType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * One-shot back-fill of {@code resource_id} on DprManpower / DprEquipment
     * rows that were saved before this seeder learned how to wire them up. Walks
     * every DPR for the project, resolves each child row's trade /
     * equipmentType against the lookup maps, and saves rows where a non-null
     * id can be derived. Idempotent — no work to do once every row has a
     * resourceId.
     */
    private void backfillResourceIdsOnExistingDprRows(UUID projectId) {
        List<DailyProgressReport> dprs = dprRepository
                .findByProjectIdOrderByReportDateAscIdAsc(projectId);
        if (dprs.isEmpty()) return;

        // Bulk-fetch all child rows in one repository round-trip per kind.
        java.util.Set<UUID> dprIds = new java.util.HashSet<>();
        for (DailyProgressReport d : dprs) dprIds.add(d.getId());

        List<DprManpower> allMp = manpowerRepository.findByDprIdIn(dprIds);
        List<DprEquipment> allEq = equipmentRepository.findByDprIdIn(dprIds);

        List<DprManpower> mpToSave = new ArrayList<>();
        for (DprManpower m : allMp) {
            if (m.getResourceId() != null) continue;
            UUID rid = resolveManpowerResourceId(m.getTrade());
            if (rid != null) {
                m.setResourceId(rid);
                mpToSave.add(m);
            }
        }
        if (!mpToSave.isEmpty()) {
            try {
                manpowerRepository.saveAll(mpToSave);
            } catch (Exception e) {
                log.warn("[oman-demo daily] back-fill manpower save failed: {}", e.getMessage());
            }
        }

        List<DprEquipment> eqToSave = new ArrayList<>();
        for (DprEquipment e : allEq) {
            if (e.getResourceId() != null) continue;
            UUID rid = resolveEquipmentResourceId(e.getEquipmentType());
            if (rid != null) {
                e.setResourceId(rid);
                eqToSave.add(e);
            }
        }
        if (!eqToSave.isEmpty()) {
            try {
                equipmentRepository.saveAll(eqToSave);
            } catch (Exception e2) {
                log.warn("[oman-demo daily] back-fill equipment save failed: {}", e2.getMessage());
            }
        }

        if (!mpToSave.isEmpty() || !eqToSave.isEmpty()) {
            log.info("[oman-demo daily] back-filled resourceId on {} existing manpower + "
                            + "{} existing equipment rows",
                    mpToSave.size(), eqToSave.size());
        }
    }

    private DailyProgressReport buildDpr(UUID projectId, GroupKey k,
                                         List<DailyDataRawRow> grp,
                                         Activity activity, UUID activityId, UUID wbsId,
                                         UUID supervisorUserId, String supervisorDisplay) {
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
        if (unit == null || unit.isBlank()) unit = DEFAULT_UNIT;

        String activityName;
        if (activity != null) {
            activityName = activity.getName();
        } else if (!k.activityCode().isEmpty()) {
            activityName = k.activityCode();
        } else {
            activityName = "Unspecified Activity";
        }
        activityName = truncate(activityName, 150);

        return DailyProgressReport.builder()
                .projectId(projectId)
                .reportDate(k.date())
                .supervisorName(truncate(supervisorDisplay, 150))
                .supervisorUserId(supervisorUserId)
                .chainageFromM(k.chainageFromM())
                .chainageToM(k.chainageToM())
                .activityId(activityId)
                .activityName(activityName)
                .wbsNodeId(wbsId)
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
        BigDecimal wh = nz(r.manpowerHours());
        // Deterministic OT/idle jitter so the KPI cards land on credible non-zero
        // values without poisoning regression tests. Seeded by (dprId, trade) so
        // the same DPR re-seed always produces the same numbers.
        long seed = mix(dprId.hashCode(), r.manpowerTrade() == null ? 0 : r.manpowerTrade().hashCode());
        BigDecimal otHours = scale(wh, 0.0d, 0.10d, seed, 1);
        BigDecimal idleHours = scale(wh, 0.03d, 0.10d, seed, 2);
        return DprManpower.builder()
                .dprId(dprId)
                .resourceId(resolveManpowerResourceId(r.manpowerTrade()))
                .trade(truncate(r.manpowerTrade(), 100))
                .category(categorise(r.manpowerTrade()))
                .nos(r.manpowerNos() != null ? r.manpowerNos() : 1)
                .workingHours(wh)
                .otHours(otHours)
                .idleHours(idleHours)
                .unitRate(r.manpowerRate())
                .unitRateBasis("HOUR")
                .lineCost(r.manpowerCost())
                .contractorName(CONTRACTOR_NAME)
                .build();
    }

    private void mergeManpower(DprManpower acc, DailyDataRawRow r) {
        if (r.manpowerNos() != null) {
            acc.setNos((acc.getNos() == null ? 0 : acc.getNos()) + r.manpowerNos());
        }
        acc.setWorkingHours(sum(acc.getWorkingHours(), r.manpowerHours()));
        acc.setLineCost(sum(acc.getLineCost(), r.manpowerCost()));
    }

    private DprEquipment newEquipment(UUID dprId, DailyDataRawRow r) {
        BigDecimal wh = nz(r.equipmentHours());
        // Deterministic jitter: 5-15% idle on every machine, breakdown only on
        // ~5% of (machine, day) pairs and at 10-25% of working hours. Seeded by
        // (dprId, equipmentType, ownership-bias) for stable reseeds.
        long seed = mix(dprId.hashCode(), r.equipmentType() == null ? 0 : r.equipmentType().hashCode());
        BigDecimal idleHours = scale(wh, 0.05d, 0.15d, seed, 1);
        boolean broke = (Math.floorMod(seed >>> 7, 20L) == 0);
        BigDecimal breakdownHours = broke ? scale(wh, 0.10d, 0.25d, seed, 2) : BigDecimal.ZERO;
        // Cycle ownership across rows so the Owned-vs-Rented chart has both
        // slices; deterministic via the seed so the mix stays stable.
        EquipmentOwnership ownership = (Math.floorMod(seed >>> 13, 4L) == 0)
                ? EquipmentOwnership.HIRED : EquipmentOwnership.OWNED;
        return DprEquipment.builder()
                .dprId(dprId)
                .resourceId(resolveEquipmentResourceId(r.equipmentType()))
                .equipmentType(truncate(r.equipmentType(), 100))
                .ownership(ownership)
                .nos(r.equipmentNos() != null ? r.equipmentNos() : 1)
                .workingHours(wh)
                .idleHours(idleHours)
                .breakdownHours(breakdownHours)
                .availabilityStatus(broke ? EquipmentAvailability.BREAKDOWN
                        : EquipmentAvailability.UTILIZED)
                .unitRate(r.equipmentRate())
                .unitRateBasis("HOUR")
                .lineCost(r.equipmentCost())
                .build();
    }

    private void mergeEquipment(DprEquipment acc, DailyDataRawRow r) {
        if (r.equipmentNos() != null) {
            acc.setNos((acc.getNos() == null ? 0 : acc.getNos()) + r.equipmentNos());
        }
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

    private static DailyDataRawRow withZeroQty(DailyDataRawRow r) {
        return new DailyDataRawRow(
                r.date(), r.site(), r.location(),
                r.chainageFromM(), r.chainageToM(),
                r.activityCode(), r.unit(), BigDecimal.ZERO, r.supervisorName(),
                r.manpowerTrade(), r.manpowerNos(), r.manpowerHours(), r.manpowerRate(),
                r.manpowerCost(),
                r.equipmentType(), r.equipmentNos(), r.equipmentHours(), r.equipmentRate(),
                r.equipmentCost(),
                r.materialDescription(), r.materialUnit(), r.materialQty(), r.materialRate(),
                r.materialCost(),
                r.remarks());
    }

    private static ManpowerCategory categorise(String trade) {
        if (trade == null) return ManpowerCategory.SKILLED;
        String t = trade.toLowerCase(Locale.ROOT).trim();
        if (SEMI_SKILLED_TRADES.contains(t)) return ManpowerCategory.SEMI_SKILLED;
        if (SKILLED_TRADES.contains(t)) return ManpowerCategory.SKILLED;
        if (UNSKILLED_TRADES.contains(t)) return ManpowerCategory.UNSKILLED;
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

    /** Composite grouping key for raw rows. Null supervisor/activity normalise to "". */
    private record GroupKey(
            LocalDate date,
            String supervisorName,
            String activityCode,
            Long chainageFromM,
            Long chainageToM) {}

    // ---------- Deterministic jitter helpers ----------

    /**
     * 64-bit hash mixer (xorshift-style) — splits a {@code (dprId, rowKey)} pair
     * into a stable per-row seed used by {@link #scale} so jitter is reproducible
     * across reseeds without pulling in a PRNG dependency.
     */
    private static long mix(int a, int b) {
        long x = (((long) a) << 32) ^ (b & 0xFFFFFFFFL) ^ 0x9E3779B97F4A7C15L;
        x ^= (x >>> 33); x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33); x *= 0xC4CEB9FE1A85EC53L;
        x ^= (x >>> 33);
        return x;
    }

    /**
     * Returns {@code base × U(minFrac, maxFrac)} as a 2-dp BigDecimal, where the
     * uniform draw is deterministic in {@code seed} and {@code slot}. Returns
     * zero when {@code base} is null or non-positive.
     */
    private static BigDecimal scale(BigDecimal base, double minFrac, double maxFrac,
                                    long seed, int slot) {
        if (base == null || base.signum() <= 0) return BigDecimal.ZERO;
        long s = mix((int) (seed ^ (seed >>> 32)), slot);
        double u = ((s & 0x7FFFFFFFFFFFFFFFL) % 1_000_000L) / 1_000_000d;
        double frac = minFrac + (maxFrac - minFrac) * u;
        return base.multiply(BigDecimal.valueOf(frac)).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- Synthetic roll-forward (R1) ----------

    /**
     * Pick the most-recent workbook DPR per {@code (activityId, supervisorUserId)}
     * group and replay it for every working day (Mon–Sat) from {@code last + 1}
     * through {@code LocalDate.now()}. Reuses the same supervisor, activity,
     * unit, side, and quantity pattern; produces fresh DprManpower /
     * DprEquipment / DprMaterial child rows with the same resourceId linkage so
     * the KPI rollups stay consistent.
     *
     * <p>Idempotent: re-running the seeder skips any (project, date) that already
     * has a DPR.
     */
    private void rollForwardSyntheticDprs(UUID projectId) {
        LocalDate today = LocalDate.now();
        List<DailyProgressReport> allDprs = dprRepository
                .findByProjectIdOrderByReportDateAscIdAsc(projectId);
        if (allDprs.isEmpty()) {
            log.info("[oman-demo daily] roll-forward: no source DPRs, skipping");
            return;
        }
        LocalDate latest = allDprs.stream()
                .map(DailyProgressReport::getReportDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (latest == null || !latest.isBefore(today)) {
            log.info("[oman-demo daily] roll-forward: latest DPR {} already ≥ today {}, skipping",
                    latest, today);
            return;
        }

        // Source pool = the last 14 calendar days of workbook DPRs. Gives us a
        // healthy mix of activities/crews to replay rather than just the very
        // last day.
        LocalDate cutoff = latest.minusDays(14);
        List<DailyProgressReport> recent = allDprs.stream()
                .filter(d -> !d.getReportDate().isBefore(cutoff))
                .toList();
        if (recent.isEmpty()) {
            log.info("[oman-demo daily] roll-forward: no recent template DPRs, skipping");
            return;
        }
        Map<UUID, DailyProgressReport> templateByKey = new LinkedHashMap<>();
        for (DailyProgressReport d : recent) {
            UUID key = d.getActivityId() != null ? d.getActivityId() : d.getId();
            templateByKey.merge(key, d, (a, b) ->
                    a.getReportDate().isAfter(b.getReportDate()) ? a : b);
        }
        List<DailyProgressReport> templates = new ArrayList<>(templateByKey.values());

        // Skip pre-existing (project, date) pairs so re-runs are a no-op.
        Set<LocalDate> alreadyHaveDate = allDprs.stream()
                .map(DailyProgressReport::getReportDate)
                .collect(java.util.stream.Collectors.toSet());

        int synthetic = 0;
        int childMp = 0, childEq = 0, childMat = 0;
        for (LocalDate d = latest.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (alreadyHaveDate.contains(d)) continue;
            for (DailyProgressReport tmpl : templates) {
                DailyProgressReport copy = cloneDpr(tmpl, d);
                DailyProgressReport saved;
                try {
                    saved = dprRepository.save(copy);
                } catch (Exception e) {
                    log.warn("[oman-demo daily] roll-forward DPR save failed at {} (activity {}): {}",
                            d, tmpl.getActivityId(), e.getMessage());
                    continue;
                }
                synthetic++;

                // Fetch + clone the template's child rows.
                List<DprManpower> srcMp = manpowerRepository.findByDprIdIn(Set.of(tmpl.getId()));
                List<DprEquipment> srcEq = equipmentRepository.findByDprIdIn(Set.of(tmpl.getId()));
                List<DprMaterial> srcMat = materialRepository.findByDprIdIn(Set.of(tmpl.getId()));

                List<DprManpower> mp = new ArrayList<>(srcMp.size());
                for (DprManpower m : srcMp) mp.add(cloneManpower(m, saved.getId(), d));
                List<DprEquipment> eq = new ArrayList<>(srcEq.size());
                for (DprEquipment e : srcEq) eq.add(cloneEquipment(e, saved.getId(), d));
                List<DprMaterial> mat = new ArrayList<>(srcMat.size());
                for (DprMaterial m : srcMat) mat.add(cloneMaterial(m, saved.getId()));

                try { manpowerRepository.saveAll(mp); childMp += mp.size(); } catch (Exception ex) {
                    log.warn("[oman-demo daily] roll-forward mp save failed: {}", ex.getMessage());
                }
                try { equipmentRepository.saveAll(eq); childEq += eq.size(); } catch (Exception ex) {
                    log.warn("[oman-demo daily] roll-forward eq save failed: {}", ex.getMessage());
                }
                try { materialRepository.saveAll(mat); childMat += mat.size(); } catch (Exception ex) {
                    log.warn("[oman-demo daily] roll-forward mat save failed: {}", ex.getMessage());
                }
            }
        }
        log.info("[oman-demo daily] roll-forward generated {} synthetic DPRs through {} "
                        + "({} manpower, {} equipment, {} material child rows)",
                synthetic, today, childMp, childEq, childMat);
    }

    private static DailyProgressReport cloneDpr(DailyProgressReport src, LocalDate newDate) {
        return DailyProgressReport.builder()
                .projectId(src.getProjectId())
                .reportDate(newDate)
                .supervisorName(src.getSupervisorName())
                .supervisorUserId(src.getSupervisorUserId())
                .chainageFromM(src.getChainageFromM())
                .chainageToM(src.getChainageToM())
                .activityId(src.getActivityId())
                .activityName(src.getActivityName())
                .wbsNodeId(src.getWbsNodeId())
                .boqItemId(src.getBoqItemId())
                .boqItemNo(src.getBoqItemNo())
                .unit(src.getUnit())
                .qtyExecuted(src.getQtyExecuted())
                .weatherCondition(DEFAULT_WEATHER)
                .side(src.getSide())
                .shift(src.getShift())
                .approvalStatus(DprApprovalStatus.APPROVED)
                .contractorName(src.getContractorName())
                .safetyIncidentType(SafetyIncidentType.NONE)
                .build();
    }

    private DprManpower cloneManpower(DprManpower src, UUID newDprId, LocalDate newDate) {
        BigDecimal wh = nz(src.getWorkingHours());
        long seed = mix(newDprId.hashCode(), src.getTrade() == null ? 0 : src.getTrade().hashCode());
        return DprManpower.builder()
                .dprId(newDprId)
                .resourceId(src.getResourceId() != null
                        ? src.getResourceId() : resolveManpowerResourceId(src.getTrade()))
                .trade(src.getTrade())
                .category(src.getCategory())
                .nos(src.getNos())
                .workingHours(wh)
                .otHours(scale(wh, 0.0d, 0.10d, seed, 1))
                .idleHours(scale(wh, 0.03d, 0.10d, seed, 2))
                .unitRate(src.getUnitRate())
                .unitRateBasis(src.getUnitRateBasis())
                .lineCost(src.getLineCost())
                .contractorName(src.getContractorName())
                .build();
    }

    private DprEquipment cloneEquipment(DprEquipment src, UUID newDprId, LocalDate newDate) {
        BigDecimal wh = nz(src.getWorkingHours());
        long seed = mix(newDprId.hashCode(), src.getEquipmentType() == null ? 0 : src.getEquipmentType().hashCode());
        boolean broke = (Math.floorMod(seed >>> 7, 20L) == 0);
        return DprEquipment.builder()
                .dprId(newDprId)
                .resourceId(src.getResourceId() != null
                        ? src.getResourceId() : resolveEquipmentResourceId(src.getEquipmentType()))
                .equipmentType(src.getEquipmentType())
                .ownership(src.getOwnership() != null ? src.getOwnership() : EquipmentOwnership.OWNED)
                .nos(src.getNos())
                .workingHours(wh)
                .idleHours(scale(wh, 0.05d, 0.15d, seed, 1))
                .breakdownHours(broke ? scale(wh, 0.10d, 0.25d, seed, 2) : BigDecimal.ZERO)
                .availabilityStatus(broke ? EquipmentAvailability.BREAKDOWN
                        : EquipmentAvailability.UTILIZED)
                .unitRate(src.getUnitRate())
                .unitRateBasis(src.getUnitRateBasis())
                .lineCost(src.getLineCost())
                .build();
    }

    private static DprMaterial cloneMaterial(DprMaterial src, UUID newDprId) {
        return DprMaterial.builder()
                .dprId(newDprId)
                .materialName(src.getMaterialName())
                .quantity(src.getQuantity())
                .unit(src.getUnit())
                .unitRate(src.getUnitRate())
                .lineCost(src.getLineCost())
                .build();
    }
}
