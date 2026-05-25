package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 — populate {@code resource_roles} plus their owned variant rate
 * tables ({@code manpower_role_rates}, {@code equipment_role_variants},
 * {@code material_role_variants}) from {@link ParsedDataset}.
 *
 * <p>Idempotent: every write is keyed on the entity's unique constraint and
 * either inserts a new row, updates the rate when it has changed, or no-ops.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage2ResourceRoles implements Stage {

    private final ParsedDatasetStore store;
    private final ResourceRoleRepository resourceRoleRepository;
    private final ResourceTypeRepository resourceTypeRepository;
    private final ManpowerRoleRateRepository manpowerRoleRateRepository;
    private final EquipmentRoleVariantRepository equipmentRoleVariantRepository;
    private final MaterialRoleVariantRepository materialRoleVariantRepository;
    private final ManpowerCategoryMasterRepository manpowerCategoryRepository;
    private final GradeMasterRepository gradeMasterRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /**
     * Gate for the duplicate-master cleanup passes. They mutate global tables
     * ({@code manpower_category_masters}, {@code grade_masters}) and re-point FKs in
     * {@code manpower_role_rates}, so they should not run silently in environments that
     * already contain real data. Default is {@code false} (skip) — set
     * {@code -Dbootstrap.cleanup-duplicate-masters=true} (or
     * {@code BOOTSTRAP_CLEANUP_DUPLICATE_MASTERS=true}) only when you want to repair
     * a known duplicate situation.
     */
    @Value("${bootstrap.cleanup-duplicate-masters:false}")
    private boolean cleanupDuplicateMasters;

    // ─────────────────────────── Oman OMR rate card ───────────────────────────
    // The Khasab daily-data file records rates per *hour* in fractional OMR (Helper 0.57 OMR/hr,
    // Foreman 2.01 OMR/hr, etc.). Stored as-is they look unrealistic in the UI because the rest
    // of the app treats the variant rate as a per-unit-of-deployment figure (one deployment =
    // one person-day for manpower, one machine-day for equipment). The cards below restate the
    // same wages as OMR per 8-hour day so cost roll-ups and DBS numbers come out at realistic
    // magnitudes. Unit = "Day" for manpower/equipment everywhere.
    //
    // Material concrete grades stay in OMR per m3 — those file values are already realistic for
    // Oman ready-mix and the unit is intrinsic to the material, not a deployment basis.

    /** Base (Skilled, Grade A) day rate per role, in OMR. */
    private static final Map<String, BigDecimal> MANPOWER_DAY_RATE_OMR = Map.ofEntries(
            Map.entry("HELPER",      bd(8.00)),
            Map.entry("MASON",       bd(18.00)),
            Map.entry("CARPENTER",   bd(18.00)),
            Map.entry("STEEL_FIXER", bd(18.00)),
            Map.entry("SCAFFOLDER",  bd(16.00)),
            Map.entry("RIGGER",      bd(16.00)),
            Map.entry("BANKMAN",     bd(15.00)),
            Map.entry("CHARGEHAND",  bd(22.00)),
            Map.entry("FOREMAN",     bd(30.00)),
            Map.entry("SUPERVISOR",  bd(45.00))
    );

    /** Equipment hire/operating day rate per role, in OMR for an 8-hour shift. */
    private static final Map<String, BigDecimal> EQUIPMENT_DAY_RATE_OMR = Map.ofEntries(
            Map.entry("AIR_COMPRESSOR",  bd(30.00)),
            Map.entry("ASPHALT_CUTLER",  bd(40.00)),
            Map.entry("BACK_HOE",        bd(90.00)),
            Map.entry("BOB_CAT",         bd(65.00)),
            Map.entry("CONCRETE_MIXER",  bd(50.00)),
            Map.entry("CRANE",           bd(200.00)),
            Map.entry("CRUSHER",         bd(250.00)),
            Map.entry("DOZER",           bd(220.00)),
            Map.entry("DUMPER",          bd(100.00)),
            Map.entry("EXCAVATOR",       bd(180.00)),
            Map.entry("GRADER",          bd(180.00)),
            Map.entry("HIAB",            bd(150.00)),
            Map.entry("MOBILE_CRANE",    bd(220.00)),
            Map.entry("PLATE_COMPACTOR", bd(25.00)),
            Map.entry("POWERSCREEN",     bd(280.00)),
            Map.entry("ROLLER",          bd(120.00)),
            Map.entry("TIPPER",          bd(95.00)),
            Map.entry("TOWER_LIGHT",     bd(20.00)),
            Map.entry("WATER_TANKER",    bd(95.00)),
            Map.entry("WHEEL_LOADER",    bd(140.00)),
            Map.entry("BABY_ROLLER",     bd(60.00)),
            Map.entry("HAND_DRILLING",   bd(25.00))
    );

    /** Material rate per physical unit, keyed by {@code ROLE|SPEC}. */
    private static final Map<String, BigDecimal> MATERIAL_RATE_OMR = Map.ofEntries(
            Map.entry("CONCRETE|C15", bd(35.00)),
            Map.entry("CONCRETE|C25", bd(50.00)),
            Map.entry("CONCRETE|C30", bd(55.00)),
            Map.entry("CONCRETE|C35", bd(62.00))
    );

    /** Material unit (physical), keyed by role code. */
    private static final Map<String, String> MATERIAL_UNIT = Map.ofEntries(
            Map.entry("CONCRETE", "m3")
    );

    /** Multiplier applied to the base Skilled+A rate, keyed by category code (uppercased). */
    private static final Map<String, BigDecimal> CATEGORY_MULTIPLIER = Map.ofEntries(
            Map.entry("UNSKILLED",       bd(0.60)),
            Map.entry("MC-UNSKILLED",    bd(0.60)),
            Map.entry("SEMISKILLED",     bd(0.80)),
            Map.entry("SEMI-SKILLED",    bd(0.80)),
            Map.entry("MC-SEMISKILLED",  bd(0.80)),
            Map.entry("SKILLED",         bd(1.00)),
            Map.entry("MC-SKILLED",      bd(1.00)),
            Map.entry("HIGHLYSKILLED",   bd(1.20)),
            Map.entry("HIGHLY-SKILLED",  bd(1.20)),
            Map.entry("MC-HIGHLYSKILLED", bd(1.20)),
            Map.entry("STAFF",           bd(1.50)),
            Map.entry("MC-STAFF",        bd(1.50))
    );

    /** Grade multiplier applied on top of category. A=1.00 (top), descending. */
    private static final Map<String, BigDecimal> GRADE_MULTIPLIER = Map.ofEntries(
            Map.entry("A", bd(1.00)),
            Map.entry("B", bd(0.85)),
            Map.entry("C", bd(0.75))
    );

    private static final BigDecimal MANPOWER_DEFAULT_DAY_OMR = bd(10.00);
    private static final BigDecimal EQUIPMENT_DEFAULT_DAY_OMR = bd(50.00);
    private static final String MANPOWER_EQUIPMENT_UNIT = "Day";

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage2ResourceRoles.class, args);
    }

    @Override
    @Transactional
    public void run() {
        // Earlier Stage 2 runs (before the name-aware lookup fix) created shadow categories
        // like SKILLED / UNSKILLED / STAFF alongside the canonical MC-SKILLED / MC-UNSKILLED
        // already seeded by ManpowerMasterSeeder. Same name → duplicate entries in dropdowns.
        // Gated behind bootstrap.cleanup-duplicate-masters because the merge mutates shared
        // masters and re-points FKs — a no-op on a clean DB, but should not run silently in
        // an environment that may have real data referencing those masters from elsewhere.
        if (cleanupDuplicateMasters) {
            cleanupDuplicateCategoryMasters();
            cleanupDuplicateGradeMasters();
        } else {
            log.info("Stage 2 — skipping duplicate-master cleanup "
                    + "(bootstrap.cleanup-duplicate-masters=false).");
        }

        ParsedDataset d = store.load();
        log.info("Stage 2 — processing {} manpower / {} equipment / {} material variants",
                d.manpowerVariants.size(), d.equipmentVariants.size(), d.materialVariants.size());

        Map<String, ResourceType> typesByCode = loadResourceTypes();

        Counters c = new Counters();
        Map<String, ResourceRole> rolesByCode = new HashMap<>();
        Map<String, ManpowerCategoryMaster> categoryCache = new HashMap<>();
        Map<String, GradeMaster> gradeCache = new HashMap<>();

        // ---- Manpower ----
        int sortSeed = nextSortOrder();
        for (ParsedDataset.ManpowerVariant v : d.manpowerVariants) {
            ResourceRole role = upsertRole(v.roleCode, v.roleName, typesByCode.get("MANPOWER"),
                    rolesByCode, c, sortSeed++);
            if (role == null) continue;

            // The Khasab source often doesn't specify category / grade per role — defaults
            // are applied so every role still gets at least one rate row (UI requires this).
            String catCode = (v.categoryCode == null || v.categoryCode.isBlank())
                    ? "Skilled" : v.categoryCode;
            String gradeCode = (v.gradeCode == null || v.gradeCode.isBlank())
                    ? "A" : v.gradeCode;

            ManpowerCategoryMaster cat = resolveCategory(catCode, categoryCache);
            if (cat == null) {
                log.warn("Skipping manpower variant for role {} — could not resolve or create category '{}'",
                        v.roleCode, catCode);
                continue;
            }
            GradeMaster grade = resolveGrade(gradeCode, gradeCache);
            if (grade == null) {
                log.warn("Skipping manpower variant role={} cat={} — could not resolve or create grade '{}'",
                        v.roleCode, catCode, gradeCode);
                continue;
            }

            upsertManpowerRate(v.roleCode, role.getId(), cat.getId(), grade.getId(),
                    catCode, gradeCode, c);
        }

        // ---- Equipment ----
        sortSeed = nextSortOrder();
        for (ParsedDataset.EquipmentVariant v : d.equipmentVariants) {
            ResourceRole role = upsertRole(v.roleCode, v.roleName, typesByCode.get("EQUIPMENT"),
                    rolesByCode, c, sortSeed++);
            if (role == null) continue;
            upsertEquipmentVariant(v.roleCode, role.getId(), v.make, v.model,
                    v.standardOutputPerDay, c);
        }

        // ---- Material ----
        sortSeed = nextSortOrder();
        for (ParsedDataset.MaterialVariant v : d.materialVariants) {
            ResourceRole role = upsertRole(v.roleCode, v.roleName, typesByCode.get("MATERIAL"),
                    rolesByCode, c, sortSeed++);
            if (role == null) continue;
            upsertMaterialVariant(v.roleCode, role.getId(), v.specGrade, v.unit, v.rate, c);
        }

        log.info("Stage 2 done — roles: {} inserted, {} updated, {} unchanged. "
                + "Variants: manpower {}/{} insert/update, equipment {}/{} insert/update, "
                + "material {}/{} insert/update.",
                c.rolesInserted, c.rolesUpdated, c.rolesUnchanged,
                c.manpowerInserted, c.manpowerUpdated,
                c.equipmentInserted, c.equipmentUpdated,
                c.materialInserted, c.materialUpdated);
    }

    // ─────────────────────────── ResourceType lookup ───────────────────────────

    private Map<String, ResourceType> loadResourceTypes() {
        Map<String, ResourceType> map = new HashMap<>();
        for (String code : List.of("MANPOWER", "EQUIPMENT", "MATERIAL")) {
            ResourceType rt = resourceTypeRepository.findByCode(code).orElse(null);
            if (rt == null) {
                throw new IllegalStateException("Resource type '" + code + "' not found. "
                        + "Run ResourceTypeSeeder before Stage2ResourceRoles.");
            }
            map.put(code, rt);
        }
        return map;
    }

    private int nextSortOrder() {
        // Roles created here go after any pre-seeded rows. Start at 1000 so we don't collide
        // with hand-curated seeders that use < 1000.
        return 1000;
    }

    // ─────────────────────────── Role upsert ───────────────────────────

    private ResourceRole upsertRole(String code, String name, ResourceType type,
                                    Map<String, ResourceRole> cache, Counters c, int sortOrder) {
        if (code == null || code.isBlank()) {
            log.warn("Skipping role with blank code (name='{}')", name);
            return null;
        }
        if (type == null) {
            log.warn("Skipping role {} — resource type not resolved", code);
            return null;
        }
        ResourceRole cached = cache.get(code);
        if (cached != null) return cached;

        ResourceRole existing = resourceRoleRepository.findByCode(code).orElse(null);
        if (existing == null) {
            ResourceRole role = ResourceRole.builder()
                    .code(code)
                    .name(name != null && !name.isBlank() ? name : code)
                    .resourceType(type)
                    .sortOrder(sortOrder)
                    .active(true)
                    .build();
            role = resourceRoleRepository.save(role);
            c.rolesInserted++;
            cache.put(code, role);
            return role;
        }

        boolean dirty = false;
        if (name != null && !name.isBlank() && !name.equals(existing.getName())) {
            existing.setName(name);
            dirty = true;
        }
        if (existing.getResourceType() == null
                || !type.getId().equals(existing.getResourceType().getId())) {
            existing.setResourceType(type);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(true);
            dirty = true;
        }
        if (dirty) {
            existing = resourceRoleRepository.save(existing);
            c.rolesUpdated++;
        } else {
            c.rolesUnchanged++;
        }
        cache.put(code, existing);
        return existing;
    }

    // ─────────────────────────── Master resolution ───────────────────────────

    private ManpowerCategoryMaster resolveCategory(String rawCode,
                                                   Map<String, ManpowerCategoryMaster> cache) {
        if (rawCode == null || rawCode.isBlank()) return null;
        String input = rawCode.trim();
        String code = input.toUpperCase();
        ManpowerCategoryMaster cached = cache.get(code);
        if (cached != null) return cached;

        // Prefer NAME match. The runtime app seeds canonical categories with codes like
        // "MC-SKILLED" but name "Skilled". The fixture carries the human-readable name
        // ("Skilled"), so matching by name reuses the seeded row instead of creating a
        // duplicate with code "SKILLED".
        List<ManpowerCategoryMaster> all = manpowerCategoryRepository.findAll();
        ManpowerCategoryMaster existing = null;
        for (ManpowerCategoryMaster m : all) {
            if (m.getName() != null && m.getName().equalsIgnoreCase(input)) {
                existing = m;
                break;
            }
        }
        if (existing == null) {
            for (ManpowerCategoryMaster m : all) {
                if (m.getCode() != null && m.getCode().equalsIgnoreCase(code)) {
                    existing = m;
                    break;
                }
            }
        }
        if (existing == null) {
            existing = manpowerCategoryRepository.save(ManpowerCategoryMaster.builder()
                    .code(code)
                    .name(toTitle(code))
                    .sortOrder(0)
                    .active(true)
                    .build());
            log.info("Created ManpowerCategoryMaster '{}'", code);
        }
        cache.put(code, existing);
        return existing;
    }

    private GradeMaster resolveGrade(String rawCode, Map<String, GradeMaster> cache) {
        if (rawCode == null || rawCode.isBlank()) return null;
        String code = rawCode.trim().toUpperCase();
        GradeMaster cached = cache.get(code);
        if (cached != null) return cached;

        GradeMaster existing = gradeMasterRepository.findByCode(code).orElse(null);
        if (existing == null) {
            for (GradeMaster g : gradeMasterRepository.findAll()) {
                if (g.getCode() != null && g.getCode().equalsIgnoreCase(code)) {
                    existing = g;
                    break;
                }
            }
        }
        if (existing == null) {
            existing = gradeMasterRepository.save(GradeMaster.builder()
                    .code(code)
                    .name("Grade " + code)
                    .sortOrder(0)
                    .active(true)
                    .build());
            log.info("Created GradeMaster '{}'", code);
        }
        cache.put(code, existing);
        return existing;
    }

    /**
     * Merge duplicate ManpowerCategoryMaster rows with the same name. Prefer the row whose
     * code starts with {@code MC-} (canonical from ManpowerMasterSeeder) — that's the row
     * the rest of the app's seed data already references. Remap any FK references from
     * the duplicate rows to the kept one before deleting them.
     */
    private void cleanupDuplicateCategoryMasters() {
        List<ManpowerCategoryMaster> all = manpowerCategoryRepository.findAll();
        java.util.Map<String, java.util.List<ManpowerCategoryMaster>> byName = new java.util.HashMap<>();
        for (ManpowerCategoryMaster m : all) {
            if (m.getName() == null) continue;
            byName.computeIfAbsent(m.getName().trim().toLowerCase(), k -> new java.util.ArrayList<>()).add(m);
        }
        int merged = 0;
        for (var entry : byName.entrySet()) {
            java.util.List<ManpowerCategoryMaster> rows = entry.getValue();
            if (rows.size() < 2) continue;
            rows.sort((a, b) -> {
                boolean aMC = a.getCode() != null && a.getCode().startsWith("MC-");
                boolean bMC = b.getCode() != null && b.getCode().startsWith("MC-");
                if (aMC && !bMC) return -1;
                if (!aMC && bMC) return 1;
                String ac = a.getCode() == null ? "" : a.getCode();
                String bc = b.getCode() == null ? "" : b.getCode();
                return ac.compareTo(bc);
            });
            ManpowerCategoryMaster keep = rows.get(0);
            for (int i = 1; i < rows.size(); i++) {
                ManpowerCategoryMaster dup = rows.get(i);
                em.createNativeQuery("UPDATE resource.manpower_role_rates SET category_id = :keep WHERE category_id = :dup")
                        .setParameter("keep", keep.getId())
                        .setParameter("dup", dup.getId())
                        .executeUpdate();
                manpowerCategoryRepository.delete(dup);
                merged++;
                log.info("Merged duplicate ManpowerCategoryMaster '{}' (code={}) → '{}' (code={})",
                        dup.getName(), dup.getCode(), keep.getName(), keep.getCode());
            }
        }
        if (merged > 0) log.info("Cleanup: merged {} duplicate ManpowerCategoryMaster row(s).", merged);
    }

    /** Same idea for GradeMaster: prefer code with shortest length (canonical "A"/"B"/"C") and lowest code alphabetically. */
    private void cleanupDuplicateGradeMasters() {
        List<GradeMaster> all = gradeMasterRepository.findAll();
        java.util.Map<String, java.util.List<GradeMaster>> byName = new java.util.HashMap<>();
        for (GradeMaster g : all) {
            if (g.getName() == null) continue;
            byName.computeIfAbsent(g.getName().trim().toLowerCase(), k -> new java.util.ArrayList<>()).add(g);
        }
        int merged = 0;
        for (var entry : byName.entrySet()) {
            java.util.List<GradeMaster> rows = entry.getValue();
            if (rows.size() < 2) continue;
            rows.sort((a, b) -> {
                String ac = a.getCode() == null ? "" : a.getCode();
                String bc = b.getCode() == null ? "" : b.getCode();
                if (ac.length() != bc.length()) return ac.length() - bc.length();
                return ac.compareTo(bc);
            });
            GradeMaster keep = rows.get(0);
            for (int i = 1; i < rows.size(); i++) {
                GradeMaster dup = rows.get(i);
                em.createNativeQuery("UPDATE resource.manpower_role_rates SET grade_id = :keep WHERE grade_id = :dup")
                        .setParameter("keep", keep.getId())
                        .setParameter("dup", dup.getId())
                        .executeUpdate();
                gradeMasterRepository.delete(dup);
                merged++;
                log.info("Merged duplicate GradeMaster '{}' (code={}) → '{}' (code={})",
                        dup.getName(), dup.getCode(), keep.getName(), keep.getCode());
            }
        }
        if (merged > 0) log.info("Cleanup: merged {} duplicate GradeMaster row(s).", merged);
    }

    private static String toTitle(String code) {
        // "MC-SKILLED" → "Mc Skilled"; good enough for an auto-created master row.
        String s = code.replace('_', ' ').replace('-', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char ch : s.toCharArray()) {
            if (Character.isWhitespace(ch)) { sb.append(ch); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(ch)); cap = false; }
            else sb.append(ch);
        }
        return sb.toString();
    }

    // ─────────────────────────── Variant upserts ───────────────────────────

    private void upsertManpowerRate(String roleCode, java.util.UUID roleId,
                                    java.util.UUID categoryId, java.util.UUID gradeId,
                                    String categoryCode, String gradeCode, Counters c) {
        BigDecimal safeRate = computeManpowerDayRate(roleCode, categoryCode, gradeCode);
        String safeUnit = MANPOWER_EQUIPMENT_UNIT;
        ManpowerRoleRate existing = manpowerRoleRateRepository
                .findByRoleIdAndCategoryIdAndGradeId(roleId, categoryId, gradeId).orElse(null);
        if (existing == null) {
            manpowerRoleRateRepository.save(ManpowerRoleRate.builder()
                    .roleId(roleId)
                    .categoryId(categoryId)
                    .gradeId(gradeId)
                    .unit(safeUnit)
                    .rate(safeRate)
                    .active(true)
                    .build());
            c.manpowerInserted++;
            return;
        }
        boolean dirty = false;
        if (existing.getRate() == null || existing.getRate().compareTo(safeRate) != 0) {
            existing.setRate(safeRate);
            dirty = true;
        }
        if (!safeUnit.equals(existing.getUnit())) {
            existing.setUnit(safeUnit);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(true);
            dirty = true;
        }
        if (dirty) {
            manpowerRoleRateRepository.save(existing);
            c.manpowerUpdated++;
        }
    }

    private void upsertEquipmentVariant(String roleCode, java.util.UUID roleId,
                                        String make, String model,
                                        BigDecimal standardOutputPerDay, Counters c) {
        String safeMake = (make == null || make.isBlank()) ? "GENERIC" : make.trim();
        String safeModel = (model == null || model.isBlank()) ? "STD" : model.trim();
        String safeUnit = MANPOWER_EQUIPMENT_UNIT;
        BigDecimal safeRate = computeEquipmentDayRate(roleCode);

        EquipmentRoleVariant existing = equipmentRoleVariantRepository
                .findByRoleIdAndMakeAndModel(roleId, safeMake, safeModel).orElse(null);
        if (existing == null) {
            equipmentRoleVariantRepository.save(EquipmentRoleVariant.builder()
                    .roleId(roleId)
                    .make(safeMake)
                    .model(safeModel)
                    .unit(safeUnit)
                    .rate(safeRate)
                    .standardOutputPerDay(standardOutputPerDay)
                    .active(true)
                    .build());
            c.equipmentInserted++;
            return;
        }
        boolean dirty = false;
        if (existing.getRate() == null || existing.getRate().compareTo(safeRate) != 0) {
            existing.setRate(safeRate);
            dirty = true;
        }
        if (!safeUnit.equals(existing.getUnit())) {
            existing.setUnit(safeUnit);
            dirty = true;
        }
        if (standardOutputPerDay != null
                && (existing.getStandardOutputPerDay() == null
                    || existing.getStandardOutputPerDay().compareTo(standardOutputPerDay) != 0)) {
            existing.setStandardOutputPerDay(standardOutputPerDay);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(true);
            dirty = true;
        }
        if (dirty) {
            equipmentRoleVariantRepository.save(existing);
            c.equipmentUpdated++;
        }
    }

    private void upsertMaterialVariant(String roleCode, java.util.UUID roleId,
                                       String specGrade, String fileUnit,
                                       BigDecimal fileRate, Counters c) {
        String safeSpec = (specGrade == null || specGrade.isBlank()) ? "STD" : specGrade.trim();
        BigDecimal cardRate = MATERIAL_RATE_OMR.get(
                roleCode.toUpperCase() + "|" + safeSpec.toUpperCase());
        BigDecimal safeRate = cardRate != null
                ? cardRate
                : (fileRate != null ? fileRate : BigDecimal.ZERO);
        String cardUnit = MATERIAL_UNIT.get(roleCode.toUpperCase());
        String safeUnit = cardUnit != null
                ? cardUnit
                : ((fileUnit == null || fileUnit.isBlank()) ? "Unit" : fileUnit);

        MaterialRoleVariant existing = materialRoleVariantRepository
                .findByRoleIdAndSpecGrade(roleId, safeSpec).orElse(null);
        if (existing == null) {
            materialRoleVariantRepository.save(MaterialRoleVariant.builder()
                    .roleId(roleId)
                    .specGrade(safeSpec)
                    .unit(safeUnit)
                    .rate(safeRate)
                    .active(true)
                    .build());
            c.materialInserted++;
            return;
        }
        boolean dirty = false;
        if (existing.getRate() == null || existing.getRate().compareTo(safeRate) != 0) {
            existing.setRate(safeRate);
            dirty = true;
        }
        if (!safeUnit.equals(existing.getUnit())) {
            existing.setUnit(safeUnit);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(true);
            dirty = true;
        }
        if (dirty) {
            materialRoleVariantRepository.save(existing);
            c.materialUpdated++;
        }
    }

    // ─────────────────────────── Rate card lookup ───────────────────────────

    private BigDecimal computeManpowerDayRate(String roleCode, String categoryCode, String gradeCode) {
        String key = roleCode == null ? "" : roleCode.trim().toUpperCase();
        BigDecimal base = MANPOWER_DAY_RATE_OMR.get(key);
        if (base == null) {
            log.warn("Manpower role '{}' is not in the OMR day-rate card — defaulting to {} OMR/day. "
                    + "Add it to MANPOWER_DAY_RATE_OMR in Stage2ResourceRoles to set a realistic value.",
                    roleCode, MANPOWER_DEFAULT_DAY_OMR);
            base = MANPOWER_DEFAULT_DAY_OMR;
        }
        BigDecimal catMult = CATEGORY_MULTIPLIER.getOrDefault(
                categoryCode == null ? "SKILLED" : categoryCode.trim().toUpperCase(),
                BigDecimal.ONE);
        BigDecimal gradeMult = GRADE_MULTIPLIER.getOrDefault(
                gradeCode == null ? "A" : gradeCode.trim().toUpperCase(),
                BigDecimal.ONE);
        return base.multiply(catMult).multiply(gradeMult).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeEquipmentDayRate(String roleCode) {
        String key = roleCode == null ? "" : roleCode.trim().toUpperCase();
        BigDecimal rate = EQUIPMENT_DAY_RATE_OMR.get(key);
        if (rate == null) {
            log.warn("Equipment role '{}' is not in the OMR day-rate card — defaulting to {} OMR/day. "
                    + "Add it to EQUIPMENT_DAY_RATE_OMR in Stage2ResourceRoles to set a realistic value.",
                    roleCode, EQUIPMENT_DEFAULT_DAY_OMR);
            rate = EQUIPMENT_DEFAULT_DAY_OMR;
        }
        return rate.setScale(2, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── Counters ───────────────────────────

    private static final class Counters {
        int rolesInserted;
        int rolesUpdated;
        int rolesUnchanged;
        int manpowerInserted;
        int manpowerUpdated;
        int equipmentInserted;
        int equipmentUpdated;
        int materialInserted;
        int materialUpdated;
    }
}
