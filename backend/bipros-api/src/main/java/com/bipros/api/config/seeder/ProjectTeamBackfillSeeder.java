package com.bipros.api.config.seeder;

import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.ProjectTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backfills the new project_team table with a PM membership for every project that has
 * an {@code ownerId}. Re-runs are no-ops: the unique key (project_id, user_id, role) is
 * enforced at the DB level and we double-check in code so we don't churn audit columns.
 *
 * <p>Disabled via {@code bipros.seeder.project-team.enabled=false} in environments where
 * the backfill should be skipped (e.g. tests with pre-seeded data).
 */
@Slf4j
@Component
@Order(70)
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "bipros.seeder.project-team.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProjectTeamBackfillSeeder implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final ProjectTeamRepository teamRepository;

    @Override
    @Transactional
    public void run(String... args) {
        List<Project> projects = projectRepository.findAll();
        int created = 0;
        int skipped = 0;
        for (Project p : projects) {
            if (p.getOwnerId() == null) {
                skipped++;
                continue;
            }
            boolean exists = teamRepository
                .findByProjectIdAndUserIdAndRole(p.getId(), p.getOwnerId(), ProjectRole.PM)
                .isPresent();
            if (exists) {
                skipped++;
                continue;
            }
            teamRepository.save(ProjectTeamMember.builder()
                .projectId(p.getId())
                .userId(p.getOwnerId())
                .role(ProjectRole.PM)
                .reportsToUserId(null)
                .build());
            created++;
        }
        if (created > 0) {
            log.info("[ProjectTeamBackfillSeeder] created {} PM memberships (skipped {})", created, skipped);
        } else {
            log.debug("[ProjectTeamBackfillSeeder] no PM memberships needed (skipped {})", skipped);
        }
    }
}
