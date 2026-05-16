package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.api.config.seeder.OmanDemoWorkbookReader.ActivityCodeRow;
import com.bipros.api.config.seeder.OmanDemoWorkbookReader.DailyDataRawRow;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.model.WbsStatus;
import com.bipros.project.domain.model.WbsType;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the WBS tree for {@code OMAN-DEMO-KHASAB} and creates one {@link Activity} per
 * BOQ code from the daily-data {@code Code} sheet. Then it assigns a supervisor user to
 * each activity via a three-tier strategy:
 *
 * <ol>
 *   <li><b>Primary</b> — scan daily data, count DPR rows per (activityCode, supervisor),
 *       pick the supervisor with the most rows. This is the real-world operator.</li>
 *   <li><b>Fallback 1</b> — code-prefix → bucket → CM mapping (concrete activities get
 *       the concrete-leaning CM, earthworks get the earthworks CM, etc.).</li>
 *   <li><b>Fallback 2</b> — round-robin across SUPERVISOR users, so every activity has
 *       someone. A {@code WARN} is logged per fallback assignment for visibility.</li>
 * </ol>
 *
 * <p>Skip-if-exists: if any activity already exists for the project we treat the project
 * as already-seeded for this stage and return.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(202)
public class OmanDemoWbsAndActivitySeeder implements CommandLineRunner {

    private static final String L1_CIVIL = "KHA-CIVIL";
    private static final String L2_EARTH = "KHA-EARTH";
    private static final String L2_PVMT = "KHA-PVMT";
    private static final String L2_CONC = "KHA-CONC";
    private static final String L2_DRAIN = "KHA-DRAIN";

    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final ActivityRepository activityRepository;
    private final ActivitySupervisorRepository activitySupervisorRepository;
    private final UserRepository userRepository;
    private final OmanDemoWorkbookReader reader;
    private final OmanDemoStaffDirectory directory;

    @Override
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo wbs] project {} not found — did OmanDemoProjectSeeder run?",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        Project project = projectOpt.get();

        // Build supervisor indexes from the workbook — used both for fresh activity
        // creation and for the supervisor-refresh pass when activities already exist.
        Map<String, String> primarySupByCode = buildPrimarySupervisorIndex();
        Map<String, Set<String>> allSupsByCode = buildAllSupervisorsIndex();

        // Make sure the directory is loaded even on re-runs where OmanDemoStaffUserSeeder
        // saw "already exists" for every user and never called register().
        if (!directory.isPopulated()) {
            backfillDirectoryFromDb();
        }

        if (activityRepository.countByProjectId(project.getId()) > 0) {
            log.info("[oman-demo wbs] activities already seeded for {} ({} present), "
                    + "refreshing supervisors only",
                    project.getCode(), activityRepository.countByProjectId(project.getId()));
            refreshSupervisorsForExistingActivities(project.getId(), primarySupByCode,
                    allSupsByCode);
            return;
        }

        List<ActivityCodeRow> codes = reader.readActivityCodes();
        if (codes.isEmpty()) {
            log.warn("[oman-demo wbs] no BOQ codes parsed from daily-data Code sheet — skipping");
            return;
        }

        Map<String, UUID> wbsByCode = seedWbs(project.getId());

        int primary = 0;
        int fallback1 = 0;
        int fallback2 = 0;
        int orphan = 0;

        int sortCursor = 0;
        long dayOffset = 0L;
        for (ActivityCodeRow def : codes) {
            String bucket = bucketFor(def.code());
            UUID wbsId = wbsByCode.get(bucket);
            if (wbsId == null) {
                log.warn("[oman-demo wbs] no bucket for code {} ({}); skipping",
                        def.code(), bucket);
                continue;
            }

            UUID supervisorId = null;
            String supervisorName = null;
            String primaryName = primarySupByCode.get(def.code());
            if (primaryName != null) {
                supervisorId = directory.resolve(primaryName);
                if (supervisorId != null) {
                    supervisorName = displayNameFor(supervisorId, primaryName);
                    primary++;
                }
            }
            if (supervisorId == null) {
                supervisorId = bucketSupervisor(bucket);
                if (supervisorId != null) {
                    supervisorName = displayNameFor(supervisorId, null);
                    fallback1++;
                    log.warn("[oman-demo wbs] fallback bucket-assign supervisor for {}: bucket={}, "
                            + "supervisor=user[{}]", def.code(), bucket, supervisorId);
                }
            }
            if (supervisorId == null) {
                supervisorId = directory.nextSupervisor();
                if (supervisorId != null) {
                    supervisorName = displayNameFor(supervisorId, null);
                    fallback2++;
                    log.warn("[oman-demo wbs] fallback round-robin supervisor for {}: "
                            + "supervisor=user[{}]", def.code(), supervisorId);
                }
            }
            if (supervisorId == null) {
                orphan++;
                log.warn("[oman-demo wbs] could not assign any supervisor for {} — "
                        + "directory is empty", def.code());
            }

            Activity a = new Activity();
            a.setProjectId(project.getId());
            a.setWbsNodeId(wbsId);
            a.setCode(truncate(def.code(), 20));
            a.setName(truncate(def.description() == null ? def.code() : def.description(), 100));
            a.setDescription(def.description());
            a.setActivityType(ActivityType.TASK_DEPENDENT);
            a.setDurationType(DurationType.FIXED_DURATION_AND_UNITS);
            a.setPercentCompleteType(PercentCompleteType.PHYSICAL);
            a.setStatus(ActivityStatus.IN_PROGRESS);
            a.setSortOrder(sortCursor++);
            a.setOriginalDuration(30.0);
            a.setRemainingDuration(15.0);
            a.setPlannedStartDate(OmanDemoProjectSeeder.PLANNED_START.plusDays(dayOffset));
            a.setPlannedFinishDate(OmanDemoProjectSeeder.PLANNED_START.plusDays(dayOffset + 30));
            a.setPercentComplete(40.0);
            a.setSupervisorUserId(supervisorId);
            a.setSupervisorUserName(truncate(supervisorName, 100));
            a.setAssignedTo(supervisorId);   // mirror the supervisor as activity owner

            // Engineer / CM accountability: stored on the wbs node responsible_organisation_id
            // is FK to organisations — not what we want. We mirror the supervisor onto
            // responsibleUserId so legacy AI tools that read it still surface a person.
            a.setResponsibleUserId(supervisorId);
            a.setEditStatus(ActivityEditStatus.LOCKED);

            Activity saved;
            try {
                saved = activityRepository.save(a);
                dayOffset += 7;
            } catch (Exception e) {
                log.warn("[oman-demo wbs] activity save failed for {}: {}",
                        def.code(), e.getMessage());
                continue;
            }

            attachAllSupervisors(saved, allSupsByCode.get(def.code()), supervisorId);
        }

        log.info("[oman-demo wbs] seeded {} activities for {} (supervisor matches: "
                        + "{} primary, {} fallback-bucket, {} round-robin, {} orphan)",
                codes.size(), project.getCode(), primary, fallback1, fallback2, orphan);
    }

    /**
     * Re-run case: activities already exist for the project, but the supervisor join
     * table may not be populated (initial seed predated multi-supervisor support, or a
     * prior cleanup wiped the join rows). Refresh both the legacy single-supervisor
     * cache on {@link Activity} and the {@code activity_supervisors} join table from
     * the source workbook so the demo reflects current intent.
     */
    private void refreshSupervisorsForExistingActivities(UUID projectId,
                                                         Map<String, String> primarySupByCode,
                                                         Map<String, Set<String>> allSupsByCode) {
        List<Activity> activities = activityRepository.findByProjectId(projectId);
        int touched = 0;
        for (Activity a : activities) {
            UUID primary = a.getSupervisorUserId();
            String primaryName = primarySupByCode.get(a.getCode());
            if (primary == null && primaryName != null) {
                UUID resolved = directory.resolve(primaryName);
                if (resolved != null) {
                    a.setSupervisorUserId(resolved);
                    a.setSupervisorUserName(truncate(displayNameFor(resolved, primaryName), 100));
                    a.setAssignedTo(resolved);
                    a.setResponsibleUserId(resolved);
                    try {
                        activityRepository.save(a);
                        primary = resolved;
                        touched++;
                    } catch (Exception e) {
                        log.warn("[oman-demo wbs] refresh primary supervisor failed for {}: {}",
                                a.getCode(), e.getMessage());
                    }
                }
            }
            attachAllSupervisors(a, allSupsByCode.get(a.getCode()), primary);
        }
        log.info("[oman-demo wbs] supervisor refresh complete: {} activities scanned, "
                + "{} primary caches updated", activities.size(), touched);
    }

    /**
     * Insert one {@link ActivitySupervisor} row per distinct supervisor that filed a DPR
     * against this activity in the source workbook. The legacy single-supervisor cache
     * column on {@link Activity} is set elsewhere; this method maintains the join table
     * which is the source of truth for "who supervises X" going forward.
     *
     * <p>The primary supervisor (already on {@code activity.supervisor_user_id}) is
     * always included even when not in the workbook set, so the join table is never
     * empty for an activity that has a primary.
     */
    private void attachAllSupervisors(Activity activity, Set<String> namesFromWorkbook,
                                      UUID primarySupervisorId) {
        Set<UUID> userIds = new LinkedHashSet<>();
        if (primarySupervisorId != null) userIds.add(primarySupervisorId);
        if (namesFromWorkbook != null) {
            for (String name : namesFromWorkbook) {
                UUID uid = directory.resolve(name);
                if (uid != null) userIds.add(uid);
            }
        }
        for (UUID uid : userIds) {
            if (activitySupervisorRepository.existsByActivityIdAndUserId(activity.getId(), uid)) {
                continue;
            }
            String snapshot = displayNameFor(uid, null);
            ActivitySupervisor link = new ActivitySupervisor();
            link.setActivityId(activity.getId());
            link.setUserId(uid);
            link.setUserNameSnapshot(truncate(snapshot, 255));
            try {
                activitySupervisorRepository.save(link);
            } catch (Exception e) {
                log.warn("[oman-demo wbs] failed to add supervisor {} to activity {}: {}",
                        uid, activity.getCode(), e.getMessage());
            }
        }
    }

    /** Build the 4-level WBS tree (project root + 1 L1 + 4 L2 buckets). */
    private Map<String, UUID> seedWbs(UUID projectId) {
        Map<String, UUID> out = new LinkedHashMap<>();

        WbsNode root = saveWbs(projectId, null, L1_CIVIL, "Civil Works", 1, WbsType.NODE, 0);
        out.put(L1_CIVIL, root.getId());

        out.put(L2_EARTH, saveWbs(projectId, root.getId(), L2_EARTH,
                "Earthworks (clearing, excavation, embankment)", 2, WbsType.PACKAGE, 0).getId());
        out.put(L2_PVMT, saveWbs(projectId, root.getId(), L2_PVMT,
                "Pavement (sub-base, base, asphalt)", 2, WbsType.PACKAGE, 1).getId());
        out.put(L2_CONC, saveWbs(projectId, root.getId(), L2_CONC,
                "Concrete & Structures (culverts, RW, barriers)", 2, WbsType.PACKAGE, 2).getId());
        out.put(L2_DRAIN, saveWbs(projectId, root.getId(), L2_DRAIN,
                "Drainage, ancillary & misc.", 2, WbsType.PACKAGE, 3).getId());
        return out;
    }

    private WbsNode saveWbs(UUID projectId, UUID parentId, String code, String name,
                            int level, WbsType type, int sortOrder) {
        WbsNode existing = wbsNodeRepository.findByProjectIdAndCode(projectId, code).orElse(null);
        if (existing != null) return existing;
        WbsNode n = new WbsNode();
        n.setProjectId(projectId);
        n.setParentId(parentId);
        n.setCode(code);
        n.setName(truncate(name, 100));
        n.setWbsLevel(level);
        n.setWbsType(type);
        n.setWbsStatus(WbsStatus.IN_PROGRESS);
        n.setSortOrder(sortOrder);
        n.setPlannedStart(OmanDemoProjectSeeder.PLANNED_START);
        n.setPlannedFinish(OmanDemoProjectSeeder.PLANNED_FINISH);
        return wbsNodeRepository.save(n);
    }

    /** Maps a BOQ code (e.g. {@code 2.3.6(i)b}) to the L2 bucket code it belongs in. */
    static String bucketFor(String code) {
        if (code == null) return L2_DRAIN;
        String c = code.trim();
        // Pavement: 2.4.x, 2.5.x
        if (c.startsWith("2.4") || c.startsWith("2.5")) return L2_PVMT;
        // Concrete & structures: 2.6.x, 2.7.x, anything mentioning concrete in suffix
        if (c.startsWith("2.6") || c.startsWith("2.7")) return L2_CONC;
        // Earthworks: 2.1, 2.3, 2.2 (clearing, excavation, fill)
        if (c.startsWith("2.1") || c.startsWith("2.2") || c.startsWith("2.3")) return L2_EARTH;
        // Drainage: 2.8, 2.9, 3.x
        return L2_DRAIN;
    }

    private UUID bucketSupervisor(String bucket) {
        return switch (bucket) {
            case L2_EARTH -> directory.nextSupervisor();
            case L2_PVMT -> directory.nextSupervisor();
            case L2_CONC -> directory.nextCm();
            case L2_DRAIN -> directory.nextSupervisor();
            default -> directory.nextSupervisor();
        };
    }

    /**
     * For each BOQ code, find the supervisor who appears most often on DPR-style daily
     * rows for that code. Returns code → supervisor display name.
     */
    private Map<String, String> buildPrimarySupervisorIndex() {
        List<DailyDataRawRow> rows;
        try {
            rows = reader.readAllDailyRows();
        } catch (Exception e) {
            log.warn("[oman-demo wbs] failed to read daily rows for supervisor index: {}",
                    e.getMessage());
            return Map.of();
        }
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        for (DailyDataRawRow r : rows) {
            if (r.activityCode() == null || r.supervisorName() == null) continue;
            tally.computeIfAbsent(r.activityCode(), k -> new HashMap<>())
                    .merge(r.supervisorName(), 1, Integer::sum);
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : tally.entrySet()) {
            e.getValue().entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .ifPresent(top -> out.put(e.getKey(), top.getKey()));
        }
        return out;
    }

    /**
     * For each BOQ code, collect every distinct supervisor display name that appears
     * on any DPR row for that code. Used to populate the {@code activity_supervisors}
     * join table so AI tools can answer "who supervises X" with the full roster
     * instead of just the most-frequent name.
     */
    private Map<String, Set<String>> buildAllSupervisorsIndex() {
        List<DailyDataRawRow> rows;
        try {
            rows = reader.readAllDailyRows();
        } catch (Exception e) {
            log.warn("[oman-demo wbs] failed to read daily rows for full-supervisor index: {}",
                    e.getMessage());
            return Map.of();
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (DailyDataRawRow r : rows) {
            if (r.activityCode() == null) continue;
            if (r.supervisorName() == null || r.supervisorName().isBlank()) continue;
            out.computeIfAbsent(r.activityCode(), k -> new LinkedHashSet<>())
                    .add(r.supervisorName().trim());
        }
        return out;
    }

    /** Re-populate {@link OmanDemoStaffDirectory} from DB for the re-run case. */
    private void backfillDirectoryFromDb() {
        List<User> users = new ArrayList<>();
        try {
            users = userRepository.findByRoleNamesAndEnabled(
                    List.of("SUPERVISOR", "ENGINEER", "QUALITY_ENGINEER",
                            "CM_MANAGER", "PROJECT_MANAGER"));
        } catch (Exception e) {
            log.warn("[oman-demo wbs] could not refresh staff directory from DB: {}",
                    e.getMessage());
        }
        for (User u : users) {
            // Oman-Demo staff are uniquely identified by the demo email domain — survives
            // username scheme changes (slug → EMP-XXX) and won't pick up unrelated EMPs
            // from other seeders.
            if (u.getEmail() == null
                    || !u.getEmail().endsWith(OmanDemoStaffUserSeeder.EMAIL_DOMAIN)) {
                continue;
            }
            String name = displayName(u);
            String role = inferRoleCategory(u);
            if (role != null) {
                directory.register(name, u.getId(), role);
            }
        }
    }

    /** Choose the dominant role category for a user based on attached Role rows. */
    private static String inferRoleCategory(User u) {
        if (u.getRoles() == null) return null;
        boolean isPm = u.getRoles().stream()
                .anyMatch(r -> r.getRole() != null
                        && "PROJECT_MANAGER".equalsIgnoreCase(r.getRole().getName()));
        if (isPm) return "PM";
        boolean isCm = u.getRoles().stream()
                .anyMatch(r -> r.getRole() != null
                        && "CM_MANAGER".equalsIgnoreCase(r.getRole().getName()));
        if (isCm) return "CM";
        boolean isEng = u.getRoles().stream()
                .anyMatch(r -> r.getRole() != null
                        && ("ENGINEER".equalsIgnoreCase(r.getRole().getName())
                            || "QUALITY_ENGINEER".equalsIgnoreCase(r.getRole().getName())));
        if (isEng) return "ENGINEER";
        boolean isSup = u.getRoles().stream()
                .anyMatch(r -> r.getRole() != null
                        && "SUPERVISOR".equalsIgnoreCase(r.getRole().getName()));
        if (isSup) return "SUPERVISOR";
        return null;
    }

    private String displayNameFor(UUID userId, String hint) {
        if (hint != null) return hint;
        try {
            return userRepository.findById(userId)
                    .map(OmanDemoWbsAndActivitySeeder::displayName)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(User u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        if (first == null && last == null) return u.getUsername();
        String composed = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return composed.isEmpty() ? u.getUsername() : composed;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
