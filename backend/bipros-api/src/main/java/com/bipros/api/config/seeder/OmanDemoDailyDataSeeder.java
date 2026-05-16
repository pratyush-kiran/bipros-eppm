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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
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
@Order(206)
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
    private final OmanDemoWorkbookReader reader;
    private final OmanDemoStaffDirectory directory;

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

        // Idempotency: skip only if DPRs in the workbook's actual date range are
        // already present. After YEAR_SHIFT the range is 2026-01-24 onward (cells
        // say 2025; we land them in 2026 to match filename + demo "now"). Historical
        // performance snapshots (Oct, Nov, Jan-5 — also +1y to Oct-25/Nov-25/Jan-26)
        // share the project but live in a different date range, so they must not
        // block the real daily-data import.
        List<DailyProgressReport> dailyWindow =
                dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
                        project.getId(),
                        LocalDate.of(2025 + YEAR_SHIFT, 1, 24),
                        LocalDate.of(2025 + YEAR_SHIFT, 4, 30));
        if (!dailyWindow.isEmpty()) {
            log.info("[oman-demo daily] {} daily DPRs already exist for {} in the "
                            + "workbook date range, skipping",
                    dailyWindow.size(), project.getCode());
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
        if (r.manpowerNos() != null) {
            acc.setNos((acc.getNos() == null ? 0 : acc.getNos()) + r.manpowerNos());
        }
        acc.setWorkingHours(sum(acc.getWorkingHours(), r.manpowerHours()));
        acc.setLineCost(sum(acc.getLineCost(), r.manpowerCost()));
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
}
