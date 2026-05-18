package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.api.config.seeder.OmanDemoWorkbookReader.DailyDataRawRow;
import com.bipros.api.config.seeder.util.SeederResourceFactory;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ProjectResource;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceMaterialDetails;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ProjectResourceRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceEquipmentDetailsRepository;
import com.bipros.resource.domain.repository.ResourceMaterialDetailsRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rolls up the {@code OMAN-DEMO-KHASAB} daily-data workbook into {@link ResourceAssignment} rows
 * on each activity. Where {@link OmanDemoDailyDataSeeder} writes per-day DPR child rows
 * ({@code DprManpower / DprEquipment / DprMaterial}), this seeder aggregates the same workbook
 * by {@code (activityCode, kind, normalisedName)} and writes one {@code ResourceAssignment} per
 * bucket so the Activity Resources view and {@code list_activity_resources} AI tool surface a
 * non-empty resource list per activity.
 *
 * <p>Resources are shared across activities — one {@link Resource} per distinct
 * trade / equipment / material name (code = {@code OMD-LAB-…} / {@code OMD-EQ-…} /
 * {@code OMD-MAT-…}), referenced from every activity that uses it. A {@link ProjectResource}
 * pool row is ensured for every Resource the project touches.
 *
 * <p>Idempotency: if any ResourceAssignment already exists for the project, the seeder no-ops.
 * To rebuild, delete {@code resource.resource_assignments WHERE project_id = …} and re-boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(207)
public class OmanDemoActivityResourceSeeder implements CommandLineRunner {

    private static final String CODE_PREFIX_MANPOWER = "OMD-LAB-";
    private static final String CODE_PREFIX_EQUIPMENT = "OMD-EQ-";
    private static final String CODE_PREFIX_MATERIAL = "OMD-MAT-";
    private static final int CODE_MAX_LEN = 50;
    private static final int NAME_MAX_LEN = 150;

    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ResourceRepository resourceRepository;
    private final ResourceAssignmentRepository assignmentRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final ResourceEquipmentDetailsRepository equipmentDetailsRepository;
    private final ResourceMaterialDetailsRepository materialDetailsRepository;
    private final ManpowerRoleRateRepository manpowerRoleRateRepository;
    private final ManpowerCategoryMasterRepository manpowerCategoryRepository;
    private final GradeMasterRepository gradeMasterRepository;
    private final SeederResourceFactory resourceFactory;
    private final OmanDemoWorkbookReader reader;

    private enum Kind { MANPOWER, EQUIPMENT, MATERIAL }

    @Override
    @Transactional
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo resources] project {} not found — did OmanDemoProjectSeeder run?",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        Project project = projectOpt.get();

        if (!assignmentRepository.findByProjectId(project.getId()).isEmpty()) {
            log.info("[oman-demo resources] resource assignments already seeded for {}, "
                    + "skipping assignment pass but still back-filling role rate book",
                    project.getCode());
            seedRoleRateBookForExistingResources(project.getId());
            return;
        }

        if (!reader.dailyDataAvailable()) {
            log.info("[oman-demo resources] daily-data workbook not on classpath; skipping");
            return;
        }

        Map<String, Activity> activityByCode = new HashMap<>();
        for (Activity a : activityRepository.findByProjectId(project.getId())) {
            if (a.getCode() != null) activityByCode.put(a.getCode().trim(), a);
        }
        if (activityByCode.isEmpty()) {
            log.warn("[oman-demo resources] no activities for {}, skipping",
                    project.getCode());
            return;
        }

        List<DailyDataRawRow> rows;
        try {
            rows = reader.readAllDailyRows();
        } catch (Exception e) {
            log.warn("[oman-demo resources] failed to read daily rows: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("[oman-demo resources] workbook present but no daily rows parsed");
            return;
        }

        // Aggregate: (activityCode, kind, normalised name) -> Bucket
        LinkedHashMap<BucketKey, Bucket> agg = new LinkedHashMap<>();
        for (DailyDataRawRow r : rows) {
            if (r.activityCode() == null) continue;
            String code = r.activityCode().trim();
            if (r.manpowerTrade() != null && !r.manpowerTrade().isBlank()) {
                addToBucket(agg, code, Kind.MANPOWER, r.manpowerTrade(),
                        r.manpowerNos(), r.manpowerHours(), null, r.manpowerCost(), "Hour");
            }
            if (r.equipmentType() != null && !r.equipmentType().isBlank()) {
                addToBucket(agg, code, Kind.EQUIPMENT, r.equipmentType(),
                        r.equipmentNos(), r.equipmentHours(), null, r.equipmentCost(), "Hour");
            }
            if (r.materialDescription() != null && !r.materialDescription().isBlank()
                    && r.materialQty() != null) {
                addToBucket(agg, code, Kind.MATERIAL, r.materialDescription(),
                        null, null, r.materialQty(), r.materialCost(),
                        nullToEach(r.materialUnit()));
            }
        }

        // Per-kind Resource cache keyed by canonical code so duplicate trades reuse the same row.
        Map<String, UUID> resourceByCode = new HashMap<>();
        Set<UUID> ensuredPoolResourceIds = new HashSet<>();
        // Manpower roles actually touched by this seed run — used to back-fill the role rate book.
        Map<UUID, String> manpowerRolesUsed = new HashMap<>();

        int mpAssignments = 0, eqAssignments = 0, matAssignments = 0;
        int resourcesEnsured = 0, poolEntriesEnsured = 0;
        Set<UUID> activitiesTouched = new HashSet<>();
        int unknownActivity = 0;

        for (Map.Entry<BucketKey, Bucket> entry : agg.entrySet()) {
            BucketKey key = entry.getKey();
            Bucket b = entry.getValue();

            Activity activity = activityByCode.get(key.activityCode());
            if (activity == null) {
                unknownActivity++;
                continue;
            }

            String resourceCode = canonicalCode(key.kind(), key.normalisedName());
            UUID resourceId = resourceByCode.get(resourceCode);
            if (resourceId == null) {
                Optional<Resource> existing = resourceRepository.findByCode(resourceCode);
                if (existing.isPresent()) {
                    Resource r = existing.get();
                    resourceId = r.getId();
                    if (key.kind() == Kind.MANPOWER && r.getRole() != null) {
                        manpowerRolesUsed.putIfAbsent(r.getRole().getId(), r.getRole().getCode());
                    }
                } else {
                    Resource created = createResource(resourceCode, key, b);
                    if (created == null) continue;
                    resourceId = created.getId();
                    resourcesEnsured++;
                    if (key.kind() == Kind.MANPOWER && created.getRole() != null) {
                        manpowerRolesUsed.putIfAbsent(
                                created.getRole().getId(), created.getRole().getCode());
                    }
                }
                resourceByCode.put(resourceCode, resourceId);
            }

            if (ensuredPoolResourceIds.add(resourceId)
                    && !projectResourceRepository.existsByProjectIdAndResourceId(
                            project.getId(), resourceId)) {
                try {
                    projectResourceRepository.save(ProjectResource.builder()
                            .projectId(project.getId())
                            .resourceId(resourceId)
                            .build());
                    poolEntriesEnsured++;
                } catch (Exception e) {
                    log.warn("[oman-demo resources] pool entry save failed for "
                            + "(project={}, resource={}): {}",
                            project.getId(), resourceId, e.getMessage());
                }
            }

            ResourceAssignment ra = buildAssignment(project.getId(), activity, resourceId,
                    key.kind(), b);
            try {
                assignmentRepository.save(ra);
                activitiesTouched.add(activity.getId());
                switch (key.kind()) {
                    case MANPOWER -> mpAssignments++;
                    case EQUIPMENT -> eqAssignments++;
                    case MATERIAL -> matAssignments++;
                }
            } catch (Exception e) {
                log.warn("[oman-demo resources] assignment save failed for "
                        + "(activity={}, resource={}): {}",
                        activity.getCode(), resourceCode, e.getMessage());
            }
        }

        log.info("[oman-demo resources] seeded {} ResourceAssignment rows "
                        + "({} manpower, {} equipment, {} material) across {} activities; "
                        + "ensured {} Resources and {} ProjectResource pool rows "
                        + "(unknown activity codes={})",
                mpAssignments + eqAssignments + matAssignments,
                mpAssignments, eqAssignments, matAssignments,
                activitiesTouched.size(), resourcesEnsured, poolEntriesEnsured,
                unknownActivity);

        seedRoleRateBook(manpowerRolesUsed);
    }

    /**
     * Back-fills {@link ManpowerRoleRate} rows for every manpower {@link ResourceRole} the
     * OMAN-DEMO-KHASAB seed actually used, so the "Configure Role Rates" panel surfaces a
     * non-empty rate book per role. One row per (role, classified-category, Grade A) at
     * unit "Day" with a deterministic OMR/day rate per role code. Idempotent — existing
     * (role, category, grade) tuples are left untouched.
     */
    private void seedRoleRateBook(Map<UUID, String> rolesUsed) {
        if (rolesUsed.isEmpty()) return;

        ManpowerCategoryMaster skilled = manpowerCategoryRepository.findByCode("MC-SKILLED").orElse(null);
        ManpowerCategoryMaster unskilled = manpowerCategoryRepository.findByCode("MC-UNSKILLED").orElse(null);
        ManpowerCategoryMaster staff = manpowerCategoryRepository.findByCode("MC-STAFF").orElse(null);
        if (skilled == null || unskilled == null || staff == null) {
            log.warn("[oman-demo role-rates] manpower category masters not seeded yet — "
                    + "skipping role-rate back-fill (Skilled/Unskilled/Staff required)");
            return;
        }

        GradeMaster gradeA = ensureGrade("A", "Grade A", 10);
        // Ensure B and C exist so the dropdown shows the full A/B/C ladder admins expect.
        ensureGrade("B", "Grade B", 20);
        ensureGrade("C", "Grade C", 30);

        int rowsCreated = 0;
        for (Map.Entry<UUID, String> e : rolesUsed.entrySet()) {
            UUID roleId = e.getKey();
            String roleCode = e.getValue();
            UUID categoryId = categoryFor(roleCode, skilled, unskilled, staff).getId();
            if (manpowerRoleRateRepository
                    .findByRoleIdAndCategoryIdAndGradeId(roleId, categoryId, gradeA.getId())
                    .isPresent()) {
                continue;
            }
            BigDecimal rate = roleDailyRateOmr(roleCode);
            try {
                manpowerRoleRateRepository.save(ManpowerRoleRate.builder()
                        .roleId(roleId)
                        .categoryId(categoryId)
                        .gradeId(gradeA.getId())
                        .unit("Day")
                        .rate(rate)
                        .active(true)
                        .build());
                rowsCreated++;
            } catch (Exception ex) {
                log.warn("[oman-demo role-rates] save failed for role {}: {}",
                        roleCode, ex.getMessage());
            }
        }
        log.info("[oman-demo role-rates] seeded {} ManpowerRoleRate rows across {} roles",
                rowsCreated, rolesUsed.size());
    }

    /**
     * Re-entry path for already-seeded databases: walk the existing manpower assignments on the
     * project, harvest the distinct roles, and run the same rate-book back-fill. Skipped if no
     * manpower resources are linked yet.
     */
    private void seedRoleRateBookForExistingResources(UUID projectId) {
        Map<UUID, String> rolesUsed = new HashMap<>();
        for (ResourceAssignment ra : assignmentRepository.findByProjectId(projectId)) {
            if (ra.getResourceId() == null) continue;
            Optional<Resource> rOpt = resourceRepository.findById(ra.getResourceId());
            if (rOpt.isEmpty()) continue;
            Resource r = rOpt.get();
            if (r.getResourceType() == null || !"MANPOWER".equals(r.getResourceType().getCode())) continue;
            if (r.getRole() == null) continue;
            rolesUsed.putIfAbsent(r.getRole().getId(), r.getRole().getCode());
        }
        seedRoleRateBook(rolesUsed);
    }

    private GradeMaster ensureGrade(String code, String name, int sortOrder) {
        return gradeMasterRepository.findByCode(code).orElseGet(() ->
                gradeMasterRepository.save(GradeMaster.builder()
                        .code(code).name(name).sortOrder(sortOrder).active(true).build()));
    }

    private static ManpowerCategoryMaster categoryFor(String roleCode,
                                                      ManpowerCategoryMaster skilled,
                                                      ManpowerCategoryMaster unskilled,
                                                      ManpowerCategoryMaster staff) {
        return switch (roleCode) {
            case "UNSKILLED_LABOUR" -> unskilled;
            case "SUPERVISOR", "FOREMAN" -> staff;
            default -> skilled;
        };
    }

    /** Deterministic OMR/day rate per role code — credible demo numbers, not arbitrary noise. */
    private static BigDecimal roleDailyRateOmr(String roleCode) {
        double daily = switch (roleCode) {
            case "SUPERVISOR" -> 45.0;
            case "FOREMAN" -> 32.0;
            case "OPERATOR" -> 28.0;
            case "DRIVER" -> 22.0;
            case "WELDER" -> 24.0;
            case "ELECTRICIAN" -> 26.0;
            case "SKILLED_LABOUR" -> 18.0;
            case "UNSKILLED_LABOUR" -> 9.0;
            default -> 14.0; // IMPORTED-MANPOWER and any future fallback role
        };
        return BigDecimal.valueOf(daily).setScale(4, RoundingMode.HALF_UP);
    }

    private void addToBucket(Map<BucketKey, Bucket> agg, String activityCode, Kind kind,
                             String rawName, Integer nos, BigDecimal hours,
                             BigDecimal qty, BigDecimal cost, String unit) {
        String normalised = rawName.trim();
        BucketKey key = new BucketKey(activityCode, kind, normalised.toUpperCase(Locale.ROOT));
        Bucket b = agg.computeIfAbsent(key, k -> new Bucket(normalised));
        if (nos != null) b.sumNos += nos;
        if (hours != null) b.sumHours = b.sumHours.add(hours);
        if (qty != null) b.sumQty = b.sumQty.add(qty);
        if (cost != null) b.sumCost = b.sumCost.add(cost);
        if (b.firstUnit == null && unit != null) b.firstUnit = unit;
    }

    private Resource createResource(String code, BucketKey key, Bucket b) {
        String typeCode = switch (key.kind()) {
            case MANPOWER -> "MANPOWER";
            case EQUIPMENT -> "EQUIPMENT";
            case MATERIAL -> "MATERIAL";
        };
        ResourceType type;
        try {
            type = resourceFactory.requireType(typeCode);
        } catch (Exception e) {
            log.warn("[oman-demo resources] required ResourceType {} missing — "
                    + "skipping bucket {}/{}: {}",
                    typeCode, key.activityCode(), b.displayName, e.getMessage());
            return null;
        }
        String roleCode = roleCodeFor(key.kind(), key.normalisedName());
        ResourceRole role = resourceFactory.ensureRole(roleCode, typeCode);

        Resource r = new Resource();
        r.setCode(truncate(code, CODE_MAX_LEN));
        r.setName(truncate(b.displayName, NAME_MAX_LEN));
        r.setResourceType(type);
        r.setRole(role);
        r.setUnit(unitFor(key.kind(), b.firstUnit));
        r.setAvailability(BigDecimal.valueOf(100));
        r.setStatus(ResourceStatus.ACTIVE);
        r.setSortOrder(0);

        BigDecimal rate = effectiveRate(b);
        if (rate != null) {
            r.setCostPerUnit(rate);
        }

        Resource saved;
        try {
            saved = resourceRepository.save(r);
        } catch (Exception e) {
            log.warn("[oman-demo resources] resource save failed for {}: {}",
                    code, e.getMessage());
            return null;
        }

        switch (key.kind()) {
            case EQUIPMENT -> {
                try {
                    equipmentDetailsRepository.save(ResourceEquipmentDetails.builder()
                            .resourceId(saved.getId())
                            .build());
                } catch (Exception e) {
                    log.warn("[oman-demo resources] equipment detail save failed for {}: {}",
                            code, e.getMessage());
                }
            }
            case MATERIAL -> {
                try {
                    materialDetailsRepository.save(ResourceMaterialDetails.builder()
                            .resourceId(saved.getId())
                            .baseUnit(unitFor(Kind.MATERIAL, b.firstUnit))
                            .build());
                } catch (Exception e) {
                    log.warn("[oman-demo resources] material detail save failed for {}: {}",
                            code, e.getMessage());
                }
            }
            default -> { /* no detail row for manpower */ }
        }
        return saved;
    }

    private ResourceAssignment buildAssignment(UUID projectId, Activity activity,
                                               UUID resourceId, Kind kind, Bucket b) {
        ResourceAssignment.ResourceAssignmentBuilder builder = ResourceAssignment.builder()
                .projectId(projectId)
                .activityId(activity.getId())
                .resourceId(resourceId)
                .rateType("STANDARD")
                .plannedStartDate(activity.getPlannedStartDate())
                .plannedFinishDate(activity.getPlannedFinishDate())
                .actualStartDate(activity.getActualStartDate())
                .unit(unitFor(kind, b.firstUnit));

        BigDecimal effective = effectiveRate(b);
        if (effective != null) builder.effectiveRate(effective);

        Double plannedUnits;
        if (kind == Kind.MATERIAL) {
            builder.quantity(b.sumQty);
            plannedUnits = b.sumQty.doubleValue();
        } else {
            builder.headcount(b.sumNos);
            builder.duration(b.sumHours);
            plannedUnits = b.sumHours.doubleValue();
        }
        builder.plannedUnits(plannedUnits);
        builder.budgetedUnits(plannedUnits);
        builder.actualUnits(plannedUnits);
        builder.remainingUnits(0.0);
        builder.atCompletionUnits(plannedUnits);

        if (b.sumCost.signum() > 0) {
            BigDecimal cost = b.sumCost.setScale(4, RoundingMode.HALF_UP);
            builder.plannedCost(cost);
            builder.budgetedCost(cost);
            builder.actualCost(cost);
            builder.remainingCost(BigDecimal.ZERO);
            builder.atCompletionCost(cost);
        }
        return builder.build();
    }

    private static BigDecimal effectiveRate(Bucket b) {
        BigDecimal denom = b.sumHours.signum() > 0 ? b.sumHours
                : (b.sumQty.signum() > 0 ? b.sumQty : null);
        if (denom == null || b.sumCost.signum() <= 0) return null;
        return b.sumCost.divide(denom, 4, RoundingMode.HALF_UP);
    }

    private static String roleCodeFor(Kind kind, String normalisedUpperName) {
        String n = normalisedUpperName;
        return switch (kind) {
            case MANPOWER -> {
                if (n.contains("FOREMAN")) yield "FOREMAN";
                if (n.contains("CHARGEHAND")) yield "SKILLED_LABOUR";
                if (n.contains("SUPERVISOR")) yield "SUPERVISOR";
                if (n.contains("HELPER") || n.contains("CLEANER")) yield "UNSKILLED_LABOUR";
                if (n.contains("WATCHMAN") || n.contains("TYRE")) yield "UNSKILLED_LABOUR";
                if (n.contains("MASON") || n.contains("CARPENTER") || n.contains("STEEL FIXER")
                        || n.contains("PLUMBER") || n.contains("PAINTER")
                        || n.contains("BANKMAN") || n.contains("RIGGER")
                        || n.contains("SCAFFOLDER") || n.contains("SURVEY")) yield "SKILLED_LABOUR";
                if (n.contains("OPERATOR")) yield "OPERATOR";
                if (n.contains("DRIVER")) yield "DRIVER";
                if (n.contains("WELDER")) yield "WELDER";
                if (n.contains("ELECTRICIAN")) yield "ELECTRICIAN";
                if (n.contains("MECHANIC")) yield "SKILLED_LABOUR";
                yield "IMPORTED-MANPOWER";
            }
            case EQUIPMENT -> {
                if (n.contains("TIPPER") || n.contains("DUMPER") || n.contains("TRUCK"))
                    yield "TRANSPORT_VEHICLES";
                if (n.contains("EXCAVATOR") || n.contains("LOADER") || n.contains("DOZER")
                        || n.contains("BULLDOZER") || n.contains("BACKHOE") || n.contains("JCB"))
                    yield "EARTH_MOVING";
                if (n.contains("GRADER") || n.contains("ROLLER") || n.contains("PAVER"))
                    yield "PAVING_EQUIPMENT";
                if (n.contains("CRANE")) yield "CRANES_LIFTING";
                if (n.contains("MIXER") || n.contains("BATCHING")) yield "CONCRETE_EQUIPMENT";
                yield "IMPORTED-EQUIPMENT";
            }
            case MATERIAL -> "IMPORTED-MATERIAL";
        };
    }

    private static String unitFor(Kind kind, String firstUnit) {
        if (kind == Kind.MATERIAL) {
            return firstUnit == null || firstUnit.isBlank() ? "Each" : firstUnit;
        }
        return "Hour";
    }

    /** {@code OMD-LAB-FOREMAN} / {@code OMD-EQ-WHEEL-LOADER} / {@code OMD-MAT-AGGREGATE-20MM}. */
    private static String canonicalCode(Kind kind, String normalisedUpperName) {
        String prefix = switch (kind) {
            case MANPOWER -> CODE_PREFIX_MANPOWER;
            case EQUIPMENT -> CODE_PREFIX_EQUIPMENT;
            case MATERIAL -> CODE_PREFIX_MATERIAL;
        };
        String slug = normalisedUpperName.replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+", "").replaceAll("-+$", "");
        int budget = CODE_MAX_LEN - prefix.length();
        if (slug.length() > budget) slug = slug.substring(0, budget);
        return prefix + slug;
    }

    private static String nullToEach(String unit) {
        if (unit == null) return "Each";
        String trimmed = unit.trim();
        return trimmed.isEmpty() ? "Each" : trimmed;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Aggregation key: activity code + kind + uppercased trade/equipment/material name. */
    private record BucketKey(String activityCode, Kind kind, String normalisedName) {}

    /** Mutable accumulator for a single (activity, kind, name) bucket. */
    private static final class Bucket {
        private final String displayName;
        private int sumNos = 0;
        private BigDecimal sumHours = BigDecimal.ZERO;
        private BigDecimal sumQty = BigDecimal.ZERO;
        private BigDecimal sumCost = BigDecimal.ZERO;
        private String firstUnit;

        private Bucket(String displayName) {
            this.displayName = displayName;
        }
    }
}
