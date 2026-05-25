package com.bipros.bootstrap.stage;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 8 — link activities to their supervisor Users via the
 * {@code activity.activity_supervisors} join table, and mirror the first
 * supervisor onto {@code Activity.supervisorUserId} / {@code supervisorUserName}
 * for the legacy single-cache readers.
 *
 * <p>Supervisor name resolution tries (case-insensitive, in order):
 * <ol>
 *   <li>{@code firstName + " " + lastName}</li>
 *   <li>{@code employeeCode}</li>
 *   <li>{@code username} derived from the name (kebab and snake forms)</li>
 * </ol>
 *
 * <p>Unmatched names are accumulated and reported in a single summary so the
 * operator can fix them in one pass. The stage fails after the summary if any
 * names remained unresolved.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage8ActivitySupervisors implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ActivitySupervisorRepository activitySupervisorRepository;
    private final UserRepository userRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage8ActivitySupervisors.class, args);
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

        List<Activity> activities = activityRepository.findByProjectId(project.getId());
        Map<String, Activity> activitiesByCode = new HashMap<>();
        for (Activity a : activities) {
            activitiesByCode.put(a.getCode(), a);
        }

        // Cache user lookups across all activities — many activities reuse the same supervisors.
        Map<String, User> resolvedCache = new HashMap<>();
        Set<String> unmatched = new LinkedHashSet<>();

        int inserted = 0;
        int skippedExisting = 0;
        int primaryMirrored = 0;
        int activitiesMissing = 0;

        for (ParsedDataset.ActivityInfo info : d.activities) {
            if (info.supervisorNames == null || info.supervisorNames.isEmpty()) continue;
            Activity activity = activitiesByCode.get(info.code);
            if (activity == null) {
                log.warn("Stage 8 — activity {} from dataset not found in DB, skipping", info.code);
                activitiesMissing++;
                continue;
            }

            List<User> resolvedForActivity = new ArrayList<>();
            for (String rawName : info.supervisorNames) {
                if (rawName == null || rawName.isBlank()) continue;
                String name = rawName.trim();
                User user = resolveUser(name, resolvedCache);
                if (user == null) {
                    unmatched.add(name);
                    continue;
                }
                resolvedForActivity.add(user);

                if (activitySupervisorRepository
                        .existsByActivityIdAndUserId(activity.getId(), user.getId())) {
                    skippedExisting++;
                    continue;
                }
                ActivitySupervisor link = new ActivitySupervisor();
                link.setActivityId(activity.getId());
                link.setUserId(user.getId());
                link.setUserNameSnapshot(displayName(user));
                activitySupervisorRepository.save(link);
                inserted++;
            }

            if (!resolvedForActivity.isEmpty()) {
                User primary = resolvedForActivity.get(0);
                String primaryName = displayName(primary);
                boolean dirty = false;
                if (activity.getSupervisorUserId() == null
                        || !primary.getId().equals(activity.getSupervisorUserId())) {
                    activity.setSupervisorUserId(primary.getId());
                    dirty = true;
                }
                if (primaryName != null && !primaryName.equals(activity.getSupervisorUserName())) {
                    activity.setSupervisorUserName(primaryName);
                    dirty = true;
                }
                if (dirty) {
                    activityRepository.save(activity);
                    primaryMirrored++;
                }
            }
        }

        log.info("Stage 8 — project {}: {} supervisor links inserted, {} already existed, "
                        + "{} primary-cache updates, {} dataset activities not in DB",
                project.getCode(), inserted, skippedExisting, primaryMirrored, activitiesMissing);

        if (!unmatched.isEmpty()) {
            log.error("Stage 8 — {} supervisor name(s) could not be resolved to a User. "
                    + "Create these users (or correct the dataset) and re-run Stage 8:", unmatched.size());
            for (String n : unmatched) {
                log.error("  - unresolved supervisor name: '{}'", n);
            }
            throw new IllegalStateException("Stage 8 aborted: "
                    + unmatched.size() + " unresolved supervisor name(s) — see log above.");
        }
    }

    // ─────────────────────────── User resolution ───────────────────────────

    private User resolveUser(String name, Map<String, User> cache) {
        String key = name.toLowerCase();
        if (cache.containsKey(key)) return cache.get(key);

        User found = findByFullName(name);
        if (found == null) {
            found = userRepository.findByEmployeeCode(name).orElse(null);
        }
        if (found == null) {
            for (String candidate : usernameCandidates(name)) {
                Optional<User> u = userRepository.findByUsername(candidate);
                if (u.isPresent()) {
                    found = u.get();
                    break;
                }
            }
        }
        cache.put(key, found);
        return found;
    }

    /**
     * Walks the user table for a case-insensitive match on {@code firstName + " " + lastName}.
     * Acceptable because supervisor lists are small and Stage 8 runs once per project.
     */
    private User findByFullName(String fullName) {
        String target = fullName.trim().toLowerCase().replaceAll("\\s+", " ");
        for (User u : userRepository.findAll()) {
            String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
            String last = u.getLastName() == null ? "" : u.getLastName().trim();
            String composed = (first + " " + last).trim().toLowerCase().replaceAll("\\s+", " ");
            if (!composed.isEmpty() && composed.equals(target)) return u;
        }
        return null;
    }

    /** Generate kebab- and snake-cased username candidates from a display name. */
    private List<String> usernameCandidates(String name) {
        String normalised = name.trim().toLowerCase().replaceAll("\\s+", " ");
        String kebab = normalised.replace(' ', '-');
        String snake = normalised.replace(' ', '_');
        String dotted = normalised.replace(' ', '.');
        List<String> out = new ArrayList<>();
        out.add(normalised);
        if (!kebab.equals(normalised)) out.add(kebab);
        if (!snake.equals(normalised)) out.add(snake);
        if (!dotted.equals(normalised)) out.add(dotted);
        return out;
    }

    private static String displayName(User u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        if ((first == null || first.isBlank()) && (last == null || last.isBlank())) {
            return u.getUsername();
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }
}
