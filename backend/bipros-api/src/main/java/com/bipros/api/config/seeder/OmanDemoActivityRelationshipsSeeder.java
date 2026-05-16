package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Creates finish-to-start activity relationships for {@code OMAN-DEMO-KHASAB} so the CPM
 * engine has a realistic network to traverse and Gantt / EVM downstream visualisations
 * render usefully.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Within each L2 bucket, sort activities by BOQ code natural order and chain
 *       {@code i → i+1} as FS-0.</li>
 *   <li>Cross-bucket gates:
 *     <ul>
 *       <li>Last earthworks activity → first pavement activity (FS-0).</li>
 *       <li>Last earthworks activity → first concrete activity (FS-0).</li>
 *       <li>Last concrete activity → first drainage activity with FS-7 (curing lag).</li>
 *       <li>Last pavement activity → first drainage activity (FS-0).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Idempotent via the unique constraint on (predecessor, successor) and an
 * {@code existsByPredecessorActivityIdAndSuccessorActivityId} pre-check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(203)
public class OmanDemoActivityRelationshipsSeeder implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ActivityRelationshipRepository relationshipRepository;

    @Override
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo rels] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        UUID projectId = projectOpt.get().getId();

        List<Activity> activities = activityRepository.findByProjectId(projectId);
        if (activities.isEmpty()) {
            log.warn("[oman-demo rels] no activities for {}, skipping", projectId);
            return;
        }

        // Group activities by bucket; sort each group by code natural order.
        Map<String, List<Activity>> byBucket = new TreeMap<>();
        Map<UUID, String> wbsCodeById = new HashMap<>();
        // We use the BOQ-code → bucket mapping from the WBS seeder for consistency.
        for (Activity a : activities) {
            String bucket = OmanDemoWbsAndActivitySeeder.bucketFor(a.getCode());
            byBucket.computeIfAbsent(bucket, k -> new ArrayList<>()).add(a);
            wbsCodeById.put(a.getId(), bucket);
        }
        for (List<Activity> group : byBucket.values()) {
            group.sort(Comparator.comparing(Activity::getCode,
                    Comparator.nullsLast(naturalOrder())));
        }

        int created = 0;
        int skipped = 0;

        // 1. Within-bucket sequential chains.
        for (Map.Entry<String, List<Activity>> e : byBucket.entrySet()) {
            List<Activity> group = e.getValue();
            for (int i = 0; i < group.size() - 1; i++) {
                if (link(projectId, group.get(i).getId(), group.get(i + 1).getId(), 0.0)) created++;
                else skipped++;
            }
        }

        // 2. Cross-bucket gates.
        Activity lastEarth = lastOf(byBucket.get("KHA-EARTH"));
        Activity firstPvmt = firstOf(byBucket.get("KHA-PVMT"));
        Activity firstConc = firstOf(byBucket.get("KHA-CONC"));
        Activity lastConc = lastOf(byBucket.get("KHA-CONC"));
        Activity lastPvmt = lastOf(byBucket.get("KHA-PVMT"));
        Activity firstDrain = firstOf(byBucket.get("KHA-DRAIN"));

        if (lastEarth != null && firstPvmt != null) {
            if (link(projectId, lastEarth.getId(), firstPvmt.getId(), 0.0)) created++;
            else skipped++;
        }
        if (lastEarth != null && firstConc != null) {
            if (link(projectId, lastEarth.getId(), firstConc.getId(), 0.0)) created++;
            else skipped++;
        }
        if (lastConc != null && firstDrain != null) {
            if (link(projectId, lastConc.getId(), firstDrain.getId(), 7.0)) created++;
            else skipped++;
        }
        if (lastPvmt != null && firstDrain != null) {
            if (link(projectId, lastPvmt.getId(), firstDrain.getId(), 0.0)) created++;
            else skipped++;
        }

        log.info("[oman-demo rels] seeded {} predecessor links ({} skipped as duplicate or invalid)",
                created, skipped);
    }

    private boolean link(UUID projectId, UUID predecessorId, UUID successorId, double lagDays) {
        if (predecessorId == null || successorId == null || predecessorId.equals(successorId)) {
            return false;
        }
        if (relationshipRepository.existsByPredecessorActivityIdAndSuccessorActivityId(
                predecessorId, successorId)) {
            return false;
        }
        ActivityRelationship r = new ActivityRelationship();
        r.setProjectId(projectId);
        r.setPredecessorActivityId(predecessorId);
        r.setSuccessorActivityId(successorId);
        r.setRelationshipType(RelationshipType.FINISH_TO_START);
        r.setLag(lagDays);
        r.setIsExternal(false);
        try {
            relationshipRepository.save(r);
            return true;
        } catch (Exception e) {
            log.warn("[oman-demo rels] relationship save failed {} -> {}: {}",
                    predecessorId, successorId, e.getMessage());
            return false;
        }
    }

    private static Activity firstOf(List<Activity> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static Activity lastOf(List<Activity> list) {
        return (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
    }

    /** Natural-order code comparator: {@code 2.3.6(i)b} sorts before {@code 2.3.6(ii)}. */
    private static Comparator<String> naturalOrder() {
        return String::compareToIgnoreCase;
    }
}
