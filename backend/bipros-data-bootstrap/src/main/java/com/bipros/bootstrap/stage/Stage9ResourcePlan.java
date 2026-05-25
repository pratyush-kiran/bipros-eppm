package com.bipros.bootstrap.stage;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.application.service.role.RoleRateResolver;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 9 — derive a frozen-snapshot resource plan ({@code resource_assignments})
 * for each activity from the parsed DPR rows.
 *
 * <p>Aggregation, per activity:
 * <ul>
 *   <li>Manpower (role, category, grade): headcount = max sum-of-nos observed on any one DPR date;
 *       plannedUnits = headcount × duration days (person-days).</li>
 *   <li>Equipment (role, make, model): nos = max sum-of-nos observed on any one DPR date;
 *       plannedUnits = nos × duration days (machine-days). Mirrors manpower because variant rate
 *       is stored as OMR per Day; working hours are logging-only.</li>
 *   <li>Material (role, specGrade): quantity = total observed quantity across all DPRs for the
 *       activity (unit lifted from variant).</li>
 * </ul>
 *
 * <p>Every path guards against zero-unit assignments — a resource that resolves to
 * plannedUnits ≤ 0 is dropped with a warning rather than inserted, because such rows
 * carry no information and pollute downstream dashboards.
 *
 * <p>Idempotent: existing rows keyed by (activityId, roleId, variantId) are left as-is.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage9ResourcePlan implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ResourceTypeRepository resourceTypeRepository;
    private final ResourceRoleRepository resourceRoleRepository;
    private final ManpowerRoleRateRepository manpowerRoleRateRepository;
    private final EquipmentRoleVariantRepository equipmentRoleVariantRepository;
    private final MaterialRoleVariantRepository materialRoleVariantRepository;
    private final ManpowerCategoryMasterRepository manpowerCategoryRepository;
    private final GradeMasterRepository gradeMasterRepository;
    private final RoleRateResolver roleRateResolver;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage9ResourcePlan.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        if (d.project == null || d.project.code == null) {
            throw new IllegalStateException("ParsedDataset.project.code is required");
        }
        Project project = projectRepository.findByCode(d.project.code)
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + d.project.code + " not found — run Stage 5 first"));

        // ResourceType lookups
        ResourceType manpowerType = resourceTypeRepository.findByCode("MANPOWER")
                .orElseThrow(() -> new IllegalStateException("ResourceType MANPOWER missing"));
        ResourceType equipmentType = resourceTypeRepository.findByCode("EQUIPMENT")
                .orElseThrow(() -> new IllegalStateException("ResourceType EQUIPMENT missing"));
        ResourceType materialType = resourceTypeRepository.findByCode("MATERIAL")
                .orElseThrow(() -> new IllegalStateException("ResourceType MATERIAL missing"));

        // Index activities by code for quick lookup.
        List<Activity> activities = activityRepository.findByProjectId(project.getId());
        Map<String, Activity> activitiesByCode = new HashMap<>();
        for (Activity a : activities) activitiesByCode.put(a.getCode(), a);

        // Group DPR records by activity code.
        Map<String, List<ParsedDataset.DprRecord>> dprByActivity = new HashMap<>();
        for (ParsedDataset.DprRecord dpr : d.dprRecords) {
            if (dpr.activityCode == null) continue;
            dprByActivity
                    .computeIfAbsent(dpr.activityCode, k -> new java.util.ArrayList<>())
                    .add(dpr);
        }

        // Caches keyed by code → entity id, populated on demand.
        Map<String, UUID> roleIdByCode = new HashMap<>();
        Map<String, UUID> categoryIdByCode = new HashMap<>();
        Map<String, UUID> gradeIdByCode = new HashMap<>();

        Counters c = new Counters();

        for (ParsedDataset.ActivityInfo info : d.activities) {
            Activity activity = activitiesByCode.get(info.code);
            if (activity == null) {
                log.warn("Stage 9 — activity {} not found in DB, skipping", info.code);
                c.activitiesMissing++;
                continue;
            }
            List<ParsedDataset.DprRecord> dprs = dprByActivity.getOrDefault(info.code, List.of());
            if (dprs.isEmpty()) {
                log.debug("Stage 9 — activity {} has no DPRs in dataset, no plan generated", info.code);
                continue;
            }

            BigDecimal durationDays = BigDecimal.valueOf(
                    Math.max(1, daysBetweenInclusive(info.plannedStart, info.plannedFinish)));

            aggregateManpower(project, activity, dprs, durationDays, manpowerType,
                    roleIdByCode, categoryIdByCode, gradeIdByCode, c);
            aggregateEquipment(project, activity, dprs, durationDays, equipmentType,
                    roleIdByCode, c);
            aggregateMaterial(project, activity, dprs, materialType, roleIdByCode, c);
        }

        log.info("Stage 9 — project {}: inserted manpower {}, equipment {}, material {} assignments "
                        + "(skipped existing: {}, unresolved variants: {}, zero-units dropped: {}, "
                        + "missing activities: {})",
                project.getCode(), c.manpowerInserted, c.equipmentInserted, c.materialInserted,
                c.skippedExisting, c.unresolvedVariants, c.zeroUnitsDropped, c.activitiesMissing);
    }

    // ─────────────────────────── Manpower ───────────────────────────

    private void aggregateManpower(Project project, Activity activity,
                                   List<ParsedDataset.DprRecord> dprs, BigDecimal durationDays,
                                   ResourceType manpowerType,
                                   Map<String, UUID> roleIdByCode,
                                   Map<String, UUID> categoryIdByCode,
                                   Map<String, UUID> gradeIdByCode,
                                   Counters c) {
        // Key = role|category|grade
        // Value[date] = nos summed for that date
        Map<String, Map<LocalDate, Integer>> dailyNos = new LinkedHashMap<>();
        Map<String, ManpowerKey> keyMeta = new HashMap<>();

        for (ParsedDataset.DprRecord dpr : dprs) {
            if (dpr.manpower == null) continue;
            for (ParsedDataset.DprManpowerRow row : dpr.manpower) {
                if (row.roleCode == null || row.categoryCode == null || row.gradeCode == null) continue;
                if (row.nos == null || row.nos <= 0) continue;
                String key = row.roleCode + "|" + row.categoryCode + "|" + row.gradeCode;
                keyMeta.putIfAbsent(key, new ManpowerKey(row.roleCode, row.categoryCode, row.gradeCode));
                dailyNos.computeIfAbsent(key, k -> new HashMap<>())
                        .merge(dpr.date, row.nos, Integer::sum);
            }
        }

        for (Map.Entry<String, Map<LocalDate, Integer>> e : dailyNos.entrySet()) {
            ManpowerKey mk = keyMeta.get(e.getKey());
            int maxNos = e.getValue().values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (maxNos <= 0) continue;

            UUID roleId = resolveRoleId(mk.roleCode, roleIdByCode);
            UUID categoryId = resolveCategoryId(mk.categoryCode, categoryIdByCode);
            UUID gradeId = resolveGradeId(mk.gradeCode, gradeIdByCode);
            if (roleId == null || categoryId == null || gradeId == null) {
                log.warn("Stage 9 — activity {} manpower ({}|{}|{}): missing role/category/grade master",
                        activity.getCode(), mk.roleCode, mk.categoryCode, mk.gradeCode);
                c.unresolvedVariants++;
                continue;
            }
            Optional<ManpowerRoleRate> variant = manpowerRoleRateRepository
                    .findByRoleIdAndCategoryIdAndGradeId(roleId, categoryId, gradeId);
            if (variant.isEmpty()) {
                log.warn("Stage 9 — activity {} manpower variant not found role={} cat={} grade={}",
                        activity.getCode(), mk.roleCode, mk.categoryCode, mk.gradeCode);
                c.unresolvedVariants++;
                continue;
            }
            UUID variantId = variant.get().getId();
            Optional<ResourceAssignment> existing = resourceAssignmentRepository
                    .findFirstByActivityIdAndRoleIdAndManpowerRoleRateId(activity.getId(), roleId, variantId);
            if (existing.isPresent()) {
                c.skippedExisting++;
                continue;
            }

            BigDecimal headcount = BigDecimal.valueOf(maxNos);
            BigDecimal plannedUnits = headcount.multiply(durationDays);
            if (plannedUnits.signum() <= 0) {
                log.warn("Stage 9 — activity {} manpower {}/{}/{} resolves to 0 plannedUnits "
                                + "(maxNos={}, duration={}), dropping",
                        activity.getCode(), mk.roleCode, mk.categoryCode, mk.gradeCode,
                        maxNos, durationDays);
                c.zeroUnitsDropped++;
                continue;
            }
            BigDecimal rate = effectiveRate(project.getId(), "MANPOWER", variantId, variant.get().getRate());
            BigDecimal plannedCost = plannedUnits.multiply(rate);

            ResourceAssignment ra = ResourceAssignment.builder()
                    .projectId(project.getId())
                    .activityId(activity.getId())
                    .roleId(roleId)
                    .manpowerRoleRateId(variantId)
                    .headcount(maxNos)
                    .duration(durationDays)
                    .plannedUnits(plannedUnits.doubleValue())
                    .budgetedUnits(plannedUnits.doubleValue())
                    .actualUnits(0.0)
                    .remainingUnits(plannedUnits.doubleValue())
                    .plannedCost(plannedCost)
                    .budgetedCost(plannedCost)
                    .actualCost(BigDecimal.ZERO)
                    .remainingCost(plannedCost)
                    .effectiveRate(rate)
                    .unit(variant.get().getUnit())
                    .rateType("STANDARD")
                    .plannedStartDate(activity.getPlannedStartDate())
                    .plannedFinishDate(activity.getPlannedFinishDate())
                    .build();
            resourceAssignmentRepository.save(ra);
            c.manpowerInserted++;
        }
    }

    // ─────────────────────────── Equipment ───────────────────────────

    private void aggregateEquipment(Project project, Activity activity,
                                    List<ParsedDataset.DprRecord> dprs, BigDecimal durationDays,
                                    ResourceType equipmentType,
                                    Map<String, UUID> roleIdByCode,
                                    Counters c) {
        // Per-day nos count, keyed by role|make|model. A row with hours but null/zero nos still
        // represents "1 of this equipment ran on this day" — we floor nos to 1 in that case so
        // the equipment is reflected in the plan instead of being silently dropped.
        Map<String, Map<LocalDate, Integer>> dailyNos = new LinkedHashMap<>();
        Map<String, EquipmentKey> keyMeta = new HashMap<>();

        for (ParsedDataset.DprRecord dpr : dprs) {
            if (dpr.equipment == null) continue;
            for (ParsedDataset.DprEquipmentRow row : dpr.equipment) {
                if (row.roleCode == null) continue;
                int nos = (row.nos == null || row.nos <= 0) ? 1 : row.nos;
                String make = row.make == null ? "GENERIC" : row.make;
                String model = row.model == null ? "STD" : row.model;
                String key = row.roleCode + "|" + make + "|" + model;
                keyMeta.putIfAbsent(key, new EquipmentKey(row.roleCode, make, model));
                dailyNos.computeIfAbsent(key, k -> new HashMap<>())
                        .merge(dpr.date, nos, Integer::sum);
            }
        }

        for (Map.Entry<String, Map<LocalDate, Integer>> e : dailyNos.entrySet()) {
            EquipmentKey ek = keyMeta.get(e.getKey());
            int maxNos = e.getValue().values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (maxNos <= 0) continue;

            UUID roleId = resolveRoleId(ek.roleCode, roleIdByCode);
            if (roleId == null) {
                log.warn("Stage 9 — activity {} equipment role {} missing", activity.getCode(), ek.roleCode);
                c.unresolvedVariants++;
                continue;
            }
            Optional<EquipmentRoleVariant> variant = equipmentRoleVariantRepository
                    .findByRoleIdAndMakeAndModel(roleId, ek.make, ek.model);
            if (variant.isEmpty()) {
                log.warn("Stage 9 — activity {} equipment variant not found role={} make={} model={}",
                        activity.getCode(), ek.roleCode, ek.make, ek.model);
                c.unresolvedVariants++;
                continue;
            }
            UUID variantId = variant.get().getId();
            Optional<ResourceAssignment> existing = resourceAssignmentRepository
                    .findFirstByActivityIdAndRoleIdAndEquipmentRoleVariantId(activity.getId(), roleId, variantId);
            if (existing.isPresent()) {
                c.skippedExisting++;
                continue;
            }

            BigDecimal nos = BigDecimal.valueOf(maxNos);
            BigDecimal plannedUnits = nos.multiply(durationDays);
            if (plannedUnits.signum() <= 0) {
                log.warn("Stage 9 — activity {} equipment {}/{}/{} resolves to 0 plannedUnits "
                                + "(maxNos={}, duration={}), dropping",
                        activity.getCode(), ek.roleCode, ek.make, ek.model, maxNos, durationDays);
                c.zeroUnitsDropped++;
                continue;
            }
            BigDecimal rate = effectiveRate(project.getId(), "EQUIPMENT", variantId, variant.get().getRate());
            BigDecimal plannedCost = plannedUnits.multiply(rate);

            ResourceAssignment ra = ResourceAssignment.builder()
                    .projectId(project.getId())
                    .activityId(activity.getId())
                    .roleId(roleId)
                    .equipmentRoleVariantId(variantId)
                    .headcount(maxNos)
                    .duration(durationDays)
                    .plannedUnits(plannedUnits.doubleValue())
                    .budgetedUnits(plannedUnits.doubleValue())
                    .actualUnits(0.0)
                    .remainingUnits(plannedUnits.doubleValue())
                    .plannedCost(plannedCost)
                    .budgetedCost(plannedCost)
                    .actualCost(BigDecimal.ZERO)
                    .remainingCost(plannedCost)
                    .effectiveRate(rate)
                    .unit(variant.get().getUnit())
                    .rateType("STANDARD")
                    .plannedStartDate(activity.getPlannedStartDate())
                    .plannedFinishDate(activity.getPlannedFinishDate())
                    .build();
            resourceAssignmentRepository.save(ra);
            c.equipmentInserted++;
        }
    }

    // ─────────────────────────── Material ───────────────────────────

    private void aggregateMaterial(Project project, Activity activity,
                                   List<ParsedDataset.DprRecord> dprs,
                                   ResourceType materialType,
                                   Map<String, UUID> roleIdByCode,
                                   Counters c) {
        Map<String, BigDecimal> totalQty = new LinkedHashMap<>();
        Map<String, MaterialKey> keyMeta = new HashMap<>();

        for (ParsedDataset.DprRecord dpr : dprs) {
            if (dpr.materials == null) continue;
            for (ParsedDataset.DprMaterialRow row : dpr.materials) {
                if (row.roleCode == null || row.specGrade == null) continue;
                BigDecimal qty = row.quantity == null ? BigDecimal.ZERO : row.quantity;
                if (qty.signum() <= 0) continue;
                String key = row.roleCode + "|" + row.specGrade;
                keyMeta.putIfAbsent(key, new MaterialKey(row.roleCode, row.specGrade));
                totalQty.merge(key, qty, BigDecimal::add);
            }
        }

        for (Map.Entry<String, BigDecimal> e : totalQty.entrySet()) {
            MaterialKey mk = keyMeta.get(e.getKey());
            BigDecimal quantity = e.getValue();
            if (quantity.signum() <= 0) continue;

            UUID roleId = resolveRoleId(mk.roleCode, roleIdByCode);
            if (roleId == null) {
                log.warn("Stage 9 — activity {} material role {} missing", activity.getCode(), mk.roleCode);
                c.unresolvedVariants++;
                continue;
            }
            Optional<MaterialRoleVariant> variant = materialRoleVariantRepository
                    .findByRoleIdAndSpecGrade(roleId, mk.specGrade);
            if (variant.isEmpty()) {
                log.warn("Stage 9 — activity {} material variant not found role={} spec={}",
                        activity.getCode(), mk.roleCode, mk.specGrade);
                c.unresolvedVariants++;
                continue;
            }
            UUID variantId = variant.get().getId();
            Optional<ResourceAssignment> existing = resourceAssignmentRepository
                    .findFirstByActivityIdAndRoleIdAndMaterialRoleVariantId(activity.getId(), roleId, variantId);
            if (existing.isPresent()) {
                c.skippedExisting++;
                continue;
            }

            if (quantity.signum() <= 0) {
                log.warn("Stage 9 — activity {} material {}/{} resolves to 0 quantity, dropping",
                        activity.getCode(), mk.roleCode, mk.specGrade);
                c.zeroUnitsDropped++;
                continue;
            }
            BigDecimal rate = effectiveRate(project.getId(), "MATERIAL", variantId, variant.get().getRate());
            BigDecimal plannedCost = quantity.multiply(rate);

            ResourceAssignment ra = ResourceAssignment.builder()
                    .projectId(project.getId())
                    .activityId(activity.getId())
                    .roleId(roleId)
                    .materialRoleVariantId(variantId)
                    .quantity(quantity)
                    .plannedUnits(quantity.doubleValue())
                    .budgetedUnits(quantity.doubleValue())
                    .actualUnits(0.0)
                    .remainingUnits(quantity.doubleValue())
                    .plannedCost(plannedCost)
                    .budgetedCost(plannedCost)
                    .actualCost(BigDecimal.ZERO)
                    .remainingCost(plannedCost)
                    .effectiveRate(rate)
                    .unit(variant.get().getUnit())
                    .rateType("STANDARD")
                    .plannedStartDate(activity.getPlannedStartDate())
                    .plannedFinishDate(activity.getPlannedFinishDate())
                    .build();
            resourceAssignmentRepository.save(ra);
            c.materialInserted++;
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private BigDecimal effectiveRate(UUID projectId, String typeCode, UUID variantId, BigDecimal fallback) {
        BigDecimal resolved = roleRateResolver.resolveRate(projectId, typeCode, variantId);
        if (resolved != null) return resolved;
        return fallback != null ? fallback : BigDecimal.ZERO;
    }

    private UUID resolveRoleId(String code, Map<String, UUID> cache) {
        if (code == null) return null;
        UUID cached = cache.get(code);
        if (cached != null) return cached;
        UUID id = resourceRoleRepository.findByCode(code).map(ResourceRole::getId).orElse(null);
        if (id != null) cache.put(code, id);
        return id;
    }

    private UUID resolveCategoryId(String code, Map<String, UUID> cache) {
        if (code == null) return null;
        String key = code.trim().toUpperCase();
        UUID cached = cache.get(key);
        if (cached != null) return cached;
        UUID id = manpowerCategoryRepository.findByCode(key)
                .map(ManpowerCategoryMaster::getId).orElse(null);
        if (id != null) cache.put(key, id);
        return id;
    }

    private UUID resolveGradeId(String code, Map<String, UUID> cache) {
        if (code == null) return null;
        String key = code.trim().toUpperCase();
        UUID cached = cache.get(key);
        if (cached != null) return cached;
        UUID id = gradeMasterRepository.findByCode(key).map(GradeMaster::getId).orElse(null);
        if (id != null) cache.put(key, id);
        return id;
    }

    private static int daysBetweenInclusive(LocalDate start, LocalDate finish) {
        if (start == null || finish == null) return 1;
        long days = ChronoUnit.DAYS.between(start, finish) + 1;
        return (int) Math.max(1, days);
    }

    // ─────────────────────────── Key records ───────────────────────────

    private record ManpowerKey(String roleCode, String categoryCode, String gradeCode) {
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof ManpowerKey k
                    && Objects.equals(roleCode, k.roleCode)
                    && Objects.equals(categoryCode, k.categoryCode)
                    && Objects.equals(gradeCode, k.gradeCode));
        }
    }
    private record EquipmentKey(String roleCode, String make, String model) {}
    private record MaterialKey(String roleCode, String specGrade) {}

    private static final class Counters {
        int manpowerInserted;
        int equipmentInserted;
        int materialInserted;
        int skippedExisting;
        int unresolvedVariants;
        int zeroUnitsDropped;
        int activitiesMissing;
    }
}
