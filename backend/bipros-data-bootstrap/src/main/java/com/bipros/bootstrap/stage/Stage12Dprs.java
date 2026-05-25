package com.bipros.bootstrap.stage;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.DprMaterialRow;
import com.bipros.project.application.service.DailyProgressReportService;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 12 — most complex stage. For each parsed DPR record:
 * <ol>
 *   <li>Resolve activity (by code), supervisor user (by name or username), BOQ item (by itemNo).</li>
 *   <li>Resolve role-rate variant ids for every manpower / equipment / material child row.</li>
 *   <li>Insert through {@link DailyProgressReportService#create} so the existing in-module
 *       listeners ({@code ActivityStartOnFirstDprListener}, {@code DprBoqSyncListener}) fire.</li>
 * </ol>
 *
 * <p>Idempotency: skips when the repository already has a DPR for
 * {@code (projectId, reportDate, activityId, supervisorUserId)}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage12Dprs implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ActivitySupervisorRepository activitySupervisorRepository;
    private final UserRepository userRepository;
    private final BoqItemRepository boqItemRepository;
    private final WorkActivityRepository workActivityRepository;
    private final ResourceRoleRepository resourceRoleRepository;
    private final ManpowerRoleRateRepository manpowerRoleRateRepository;
    private final EquipmentRoleVariantRepository equipmentRoleVariantRepository;
    private final MaterialRoleVariantRepository materialRoleVariantRepository;
    private final ManpowerCategoryMasterRepository categoryMasterRepository;
    private final GradeMasterRepository gradeMasterRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DailyProgressReportService dprService;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage12Dprs.class, args);
    }

    /**
     * NOTE: deliberately NOT {@code @Transactional}. The DPR insert path lives inside
     * {@code DailyProgressReportService.create(...)} which is itself {@code @Transactional}
     * at the class level, so every DPR commits in its own short transaction. Wrapping
     * the whole 3 000+-row loop in one transaction caused two real problems on the
     * first run: nothing visible in the UI until completion, and Hibernate session
     * growth slowing every subsequent insert.
     */
    @Override
    public void run() {
        ParsedDataset d = store.load();
        if (d.project == null || d.project.code == null || d.project.code.isBlank()) {
            throw new IllegalStateException("ParsedDataset.project.code is required");
        }

        Project project = projectRepository.findByCode(d.project.code)
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + d.project.code + " not found — run Stage 5 first"));
        UUID projectId = project.getId();

        Lookups L = buildLookups(projectId);

        int total = d.dprRecords.size();
        int inserted = 0;
        int skippedExists = 0;
        int failed = 0;
        int processed = 0;
        long t0 = System.currentTimeMillis();
        log.info("Stage 12 — beginning insert of {} parsed DPR records", total);
        for (ParsedDataset.DprRecord r : d.dprRecords) {
            processed++;
            if (processed % 100 == 0) {
                long elapsedMs = System.currentTimeMillis() - t0;
                double perSec = processed * 1000.0 / Math.max(1, elapsedMs);
                long etaSec = (long) ((total - processed) / Math.max(0.01, perSec));
                log.info("Stage 12 — progress: {}/{} ({} inserted, {} skipped, {} failed) — {} DPR/s — ETA {}s",
                        processed, total, inserted, skippedExists, failed,
                        String.format("%.1f", perSec), etaSec);
            }
            // (a) activity
            if (r.activityCode == null || r.activityCode.isBlank()) {
                log.warn("Stage 12 — DPR row missing activityCode (date={}); skipping", r.date);
                failed++;
                continue;
            }
            Activity activity = L.activityByCode.get(r.activityCode);
            if (activity == null) {
                log.warn("Stage 12 — activity '{}' not found; skipping DPR on {}", r.activityCode, r.date);
                failed++;
                continue;
            }

            // (b) supervisor user — resolved AGAINST THE ACTIVITY's supervisor list. This
            // guarantees the user_id we write is one the activity_supervisors table already
            // knows, so the FE's "supervisor of this activity" check passes.
            User supervisor = resolveSupervisorForActivity(activity, r.supervisorName, L);
            if (supervisor == null) {
                log.warn("Stage 12 — supervisor '{}' not found (activity {} on {}); skipping",
                        r.supervisorName, r.activityCode, r.date);
                failed++;
                continue;
            }

            // (c) BOQ item (optional)
            UUID boqItemId = null;
            if (r.boqItemNo != null && !r.boqItemNo.isBlank()) {
                BoqItem boq = L.boqByItemNo.get(r.boqItemNo);
                if (boq == null) {
                    log.warn("Stage 12 — BOQ item '{}' not found for activity {} on {}; proceeding without",
                            r.boqItemNo, r.activityCode, r.date);
                } else {
                    boqItemId = boq.getId();
                }
            }

            // Idempotency — skip if a DPR for this (project, date, activity, supervisor) exists.
            Optional<?> existing = dprRepository
                    .findFirstByProjectIdAndReportDateAndActivityIdAndSupervisorUserId(
                            projectId, r.date, activity.getId(), supervisor.getId());
            if (existing.isPresent()) {
                skippedExists++;
                continue;
            }

            // Unit comes from the WorkActivity tied to the Activity (silences mismatch warning);
            // fall back to the parsed row's unit if no WorkActivity / no defaultUnit.
            String unit = resolveUnit(activity, r, L);

            List<DprManpowerRow> manpower = buildManpower(r.manpower, L);
            List<DprEquipmentRow> equipment = buildEquipment(r.equipment, L);
            List<DprMaterialRow> materials = buildMaterials(r.materials, L);

            String contractorName = pickContractor(r);

            CreateDailyProgressReportRequest req = new CreateDailyProgressReportRequest(
                    r.date,
                    supervisor.getId(),
                    displayName(supervisor),
                    r.chainageFromM,
                    r.chainageToM,
                    activity.getId(),
                    activity.getName(),
                    activity.getWbsNodeId(),
                    boqItemId,
                    r.boqItemNo,
                    unit,
                    r.workDoneQty,
                    r.weather,
                    r.remarks,
                    null,                                   // side
                    null,                                   // landmark
                    null,                                   // startTime
                    null,                                   // endTime
                    Shift.fromString(r.shift) != null ? Shift.fromString(r.shift) : Shift.DAY,
                    DprApprovalStatus.SUBMITTED,
                    contractorName,
                    null, null, null,                       // delayReason, safetyObservation, safetyIncidentType
                    manpower,
                    equipment,
                    materials,
                    List.of(),                              // subContractors
                    List.of()                               // issues
            );

            try {
                dprService.create(projectId, req);
                inserted++;
            } catch (Exception e) {
                log.warn("Stage 12 — DPR create failed for activity {} on {}: {}",
                        r.activityCode, r.date, e.getMessage());
                failed++;
            }
        }

        log.info("Stage 12 — DPRs for project {}: inserted={} skipped(exists)={} failed={} (parsed={})",
                project.getCode(), inserted, skippedExists, failed, d.dprRecords.size());
    }

    // ─── Lookups built once per run ─────────────────────────────────────────────

    private record Lookups(
            Map<String, Activity> activityByCode,
            Map<UUID, WorkActivity> workActivityById,
            Map<String, User> userByFullName,
            Map<String, User> userByUsername,
            Map<String, BoqItem> boqByItemNo,
            Map<String, ResourceRole> roleByCode,
            Map<String, ManpowerCategoryMaster> categoryByCode,
            Map<String, GradeMaster> gradeByCode,
            // (roleId, make, model) → variantId. make/model may be null — Map handles
            // null keys correctly, unlike the JPA repository's "= NULL" SQL.
            Map<EqKey, UUID> equipmentVariantByKey,
            // (roleId, specGrade) → variantId. specGrade may be null.
            Map<MatKey, UUID> materialVariantByKey,
            // (roleId, categoryId, gradeId) → manpowerRoleRateId.
            Map<MpKey, UUID> manpowerRoleRateByKey,
            // activityId → ordered list of user_ids registered as supervisors on that activity.
            // The DPR's supervisor_user_id is chosen from this list using name match — guarantees
            // the FE check "is this user a supervisor of this activity" passes.
            Map<UUID, List<User>> activitySupervisorsById) {}

    private record EqKey(UUID roleId, String make, String model) {}
    private record MatKey(UUID roleId, String specGrade) {}
    private record MpKey(UUID roleId, UUID categoryId, UUID gradeId) {}

    private Lookups buildLookups(UUID projectId) {
        Map<String, Activity> activityByCode = new HashMap<>();
        for (Activity a : activityRepository.findByProjectId(projectId)) {
            if (a.getCode() != null) activityByCode.put(a.getCode(), a);
        }

        Map<UUID, WorkActivity> workActivityById = new HashMap<>();
        for (WorkActivity wa : workActivityRepository.findAll()) {
            workActivityById.put(wa.getId(), wa);
        }

        Map<String, User> userByFullName = new HashMap<>();
        Map<String, User> userByUsername = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getUsername() != null) userByUsername.put(u.getUsername(), u);
            String full = displayName(u);
            if (full != null && !full.isBlank()) userByFullName.put(full, u);
        }

        Map<String, BoqItem> boqByItemNo = new HashMap<>();
        for (BoqItem b : boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId)) {
            boqByItemNo.put(b.getItemNo(), b);
        }

        Map<String, ResourceRole> roleByCode = new HashMap<>();
        for (ResourceRole r : resourceRoleRepository.findAll()) {
            if (r.getCode() != null) roleByCode.put(r.getCode(), r);
        }

        // Categories: index by NAME (lowercased) because the fixture carries the human name
        // ("Skilled") while DB codes can be "MC-SKILLED" (canonical seed) or "SKILLED"
        // (legacy from older Stage 2 runs). Name is the stable join key.
        Map<String, ManpowerCategoryMaster> categoryByCode = new HashMap<>();
        for (ManpowerCategoryMaster m : categoryMasterRepository.findAll()) {
            if (m.getName() != null) categoryByCode.put(m.getName().trim().toLowerCase(), m);
            // also index by code so the lookup is forgiving
            if (m.getCode() != null) categoryByCode.putIfAbsent(m.getCode().trim().toLowerCase(), m);
        }

        Map<String, GradeMaster> gradeByCode = new HashMap<>();
        for (GradeMaster g : gradeMasterRepository.findAll()) {
            if (g.getCode() != null) gradeByCode.put(g.getCode().trim().toUpperCase(), g);
            if (g.getName() != null) gradeByCode.putIfAbsent(g.getName().trim().toUpperCase(), g);
        }

        // Variant caches keyed by (roleId, …). Built once, walked in O(1) per DPR row —
        // avoids the broken "WHERE make = NULL" semantics of the JPA finder methods and
        // also avoids N-per-DPR repository round-trips.
        Map<EqKey, UUID> equipmentVariantByKey = new HashMap<>();
        for (EquipmentRoleVariant v : equipmentRoleVariantRepository.findAll()) {
            equipmentVariantByKey.put(new EqKey(v.getRoleId(), v.getMake(), v.getModel()), v.getId());
        }

        Map<MatKey, UUID> materialVariantByKey = new HashMap<>();
        for (MaterialRoleVariant v : materialRoleVariantRepository.findAll()) {
            materialVariantByKey.put(new MatKey(v.getRoleId(), v.getSpecGrade()), v.getId());
        }

        Map<MpKey, UUID> manpowerRoleRateByKey = new HashMap<>();
        for (ManpowerRoleRate r : manpowerRoleRateRepository.findAll()) {
            manpowerRoleRateByKey.put(new MpKey(r.getRoleId(), r.getCategoryId(), r.getGradeId()), r.getId());
        }

        // Per-activity supervisor user_ids, indexed by user so we can resolve a DPR row's
        // supervisor name to a user that is actually in the activity's supervisor list. This
        // is the only path that avoids "supervisor not in activity" UI warnings — name-based
        // resolution can pick a different user than Stage 8 did when two users share a name.
        Map<UUID, User> usersById = new HashMap<>();
        for (User u : userRepository.findAll()) usersById.put(u.getId(), u);
        Map<UUID, List<User>> activitySupervisorsById = new HashMap<>();
        for (ActivitySupervisor link : activitySupervisorRepository.findAll()) {
            User u = usersById.get(link.getUserId());
            if (u == null) continue;
            activitySupervisorsById
                    .computeIfAbsent(link.getActivityId(), k -> new ArrayList<>())
                    .add(u);
        }

        return new Lookups(activityByCode, workActivityById, userByFullName, userByUsername,
                boqByItemNo, roleByCode, categoryByCode, gradeByCode,
                equipmentVariantByKey, materialVariantByKey, manpowerRoleRateByKey,
                activitySupervisorsById);
    }

    private User resolveSupervisor(String supervisorName, Lookups L) {
        if (supervisorName == null || supervisorName.isBlank()) return null;
        User u = L.userByFullName.get(supervisorName);
        if (u != null) return u;
        return L.userByUsername.get(supervisorName);
    }

    /**
     * Resolve the DPR's supervisor preferring a user that is already linked to this activity
     * via {@code activity_supervisors}. If no name match against the activity's roster works,
     * fall back to global resolution (and log — that case means the DPR will fail the FE's
     * supervisor-of-activity check).
     */
    private User resolveSupervisorForActivity(Activity activity, String supervisorName, Lookups L) {
        if (supervisorName == null || supervisorName.isBlank()) return null;
        List<User> roster = L.activitySupervisorsById.get(activity.getId());
        if (roster != null) {
            String target = supervisorName.trim();
            for (User u : roster) {
                String full = displayName(u);
                if (full != null && full.equalsIgnoreCase(target)) return u;
            }
            for (User u : roster) {
                if (target.equalsIgnoreCase(u.getUsername())) return u;
            }
            // Last-resort fallback inside the activity: take the first supervisor on the
            // activity. Better than writing a DPR linked to a non-activity-supervisor user.
            if (!roster.isEmpty()) {
                log.warn("Stage 12 — DPR supervisor '{}' not on activity {} ; using first roster user '{}'",
                        supervisorName, activity.getCode(), displayName(roster.get(0)));
                return roster.get(0);
            }
        }
        return resolveSupervisor(supervisorName, L);
    }

    private String resolveUnit(Activity activity, ParsedDataset.DprRecord r, Lookups L) {
        if (activity.getWorkActivityId() != null) {
            WorkActivity wa = L.workActivityById.get(activity.getWorkActivityId());
            if (wa != null && wa.getDefaultUnit() != null && !wa.getDefaultUnit().isBlank()) {
                return wa.getDefaultUnit();
            }
        }
        if (r.unit != null && !r.unit.isBlank()) return r.unit;
        return "Nos";
    }

    private String pickContractor(ParsedDataset.DprRecord r) {
        // Contractor name comes from the first manpower row that has one. Parser doesn't
        // hoist a top-level contractor onto the record.
        if (r.manpower != null) {
            for (ParsedDataset.DprManpowerRow m : r.manpower) {
                if (m.contractorName != null && !m.contractorName.isBlank()) return m.contractorName;
            }
        }
        return null;
    }

    // ─── Child row builders ─────────────────────────────────────────────────────

    private List<DprManpowerRow> buildManpower(List<ParsedDataset.DprManpowerRow> rows, Lookups L) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<DprManpowerRow> out = new ArrayList<>(rows.size());
        for (ParsedDataset.DprManpowerRow row : rows) {
            ResourceRole role = row.roleCode == null ? null : L.roleByCode.get(row.roleCode);
            if (role == null) {
                log.warn("Stage 12 — manpower role '{}' not found; dropping row", row.roleCode);
                continue;
            }
            UUID manpowerRoleRateId = resolveManpowerRoleRateId(role.getId(), row.categoryCode, row.gradeCode, L);
            ManpowerCategory categoryEnum = mapCategory(row.categoryCode);
            out.add(new DprManpowerRow(
                    null,
                    null,                                                // resourceAssignmentId
                    null,                                                // resourceId
                    role.getName(),                                      // trade
                    categoryEnum,                                        // category enum (needed by the UI to show the role row)
                    Shift.DAY,
                    row.nos,
                    row.workingHours,
                    row.otHours,
                    row.idleHours,
                    row.unitRate,
                    null,                                                // unitRateBasis — service derives
                    null,                                                // lineCost — service computes
                    row.contractorName,
                    null,                                                // remarks
                    manpowerRoleRateId,
                    role.getId()));
        }
        return out;
    }

    private List<DprEquipmentRow> buildEquipment(List<ParsedDataset.DprEquipmentRow> rows, Lookups L) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<DprEquipmentRow> out = new ArrayList<>(rows.size());
        for (ParsedDataset.DprEquipmentRow row : rows) {
            ResourceRole role = row.roleCode == null ? null : L.roleByCode.get(row.roleCode);
            if (role == null) {
                log.warn("Stage 12 — equipment role '{}' not found; dropping row", row.roleCode);
                continue;
            }
            // Stage 2 stores equipment variants with substituted make/model when source is
            // null/blank: ("GENERIC", "STD"). Mirror that substitution at lookup time so the
            // map hits — the parsed dataset keeps nulls (honest about what the source had),
            // and the bridge happens here.
            String lookupMake = (row.make == null || row.make.isBlank()) ? "GENERIC" : row.make.trim();
            String lookupModel = (row.model == null || row.model.isBlank()) ? "STD" : row.model.trim();
            UUID variantId = L.equipmentVariantByKey.get(new EqKey(role.getId(), lookupMake, lookupModel));
            if (variantId == null) {
                log.warn("Stage 12 — equipment variant (role={}, make={}, model={}) not found; proceeding without",
                        row.roleCode, lookupMake, lookupModel);
            }
            out.add(new DprEquipmentRow(
                    null,
                    null,                                                // resourceAssignmentId
                    null,                                                // resourceId
                    role.getName(),                                      // equipmentType
                    null,                                                // fleetNo
                    null,                                                // ownership
                    Shift.DAY,
                    row.nos,
                    row.workingHours,
                    row.idleHours,
                    row.breakdownHours,
                    row.fuelLitres,
                    row.unitRate,
                    null,                                                // unitRateBasis
                    null,                                                // lineCost
                    null,                                                // operatorName
                    null,                                                // availabilityStatus
                    null,                                                // remarks
                    variantId,
                    role.getId()));
        }
        return out;
    }

    private List<DprMaterialRow> buildMaterials(List<ParsedDataset.DprMaterialRow> rows, Lookups L) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<DprMaterialRow> out = new ArrayList<>(rows.size());
        for (ParsedDataset.DprMaterialRow row : rows) {
            ResourceRole role = row.roleCode == null ? null : L.roleByCode.get(row.roleCode);
            if (role == null) {
                log.warn("Stage 12 — material role '{}' not found; dropping row", row.roleCode);
                continue;
            }
            // Stage 2 substitutes blank specGrade → "STD" — mirror that here.
            String lookupSpec = (row.specGrade == null || row.specGrade.isBlank()) ? "STD" : row.specGrade.trim();
            UUID variantId = L.materialVariantByKey.get(new MatKey(role.getId(), lookupSpec));
            if (variantId == null) {
                log.warn("Stage 12 — material variant (role={}, specGrade={}) not found; proceeding without",
                        row.roleCode, lookupSpec);
            }
            out.add(new DprMaterialRow(
                    null,
                    null,                                                // resourceAssignmentId
                    null,                                                // materialId
                    null,                                                // resourceId
                    role.getName(),                                      // materialName
                    row.quantity,
                    row.unit,
                    null,                                                // source
                    null,                                                // batchNo
                    row.vendorName,
                    row.unitRate,
                    null,                                                // lineCost
                    null,                                                // remarks
                    variantId,
                    role.getId()));
        }
        return out;
    }

    private UUID resolveManpowerRoleRateId(UUID roleId, String categoryCode, String gradeCode, Lookups L) {
        if (categoryCode == null || gradeCode == null) return null;
        // Category map is keyed by lowercase name (and lowercase code as backup). Grade map
        // is keyed by uppercase code (and uppercase name as backup). See buildLookups.
        ManpowerCategoryMaster cat = L.categoryByCode.get(categoryCode.trim().toLowerCase());
        GradeMaster grade = L.gradeByCode.get(gradeCode.trim().toUpperCase());
        if (cat == null || grade == null) return null;
        return L.manpowerRoleRateByKey.get(new MpKey(roleId, cat.getId(), grade.getId()));
    }

    /** "Skilled"/"Semi-Skilled"/"Unskilled" → enum, null otherwise. Staff is mapped to SKILLED
     * since the runtime enum does not have a STAFF value yet. */
    private static ManpowerCategory mapCategory(String code) {
        if (code == null) return null;
        try {
            return ManpowerCategory.fromString(code);
        } catch (IllegalArgumentException e) {
            return ManpowerCategory.SKILLED;
        }
    }

    private static String displayName(User u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        if (first == null && last == null) return u.getUsername();
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }
}
