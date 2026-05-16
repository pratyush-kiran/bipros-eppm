package com.bipros.api.config.seeder;

import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Removes every Oman-Demo staff user before the staff seeder runs, so the staff
 * seeder is the sole authority on the tenant's identities. Covers both:
 * <ul>
 *   <li>Legacy slug-prefix accounts ({@code oman-demo.*} / {@code oman.demo.*}) — the
 *       original problem; their slug was leaking into the AI's supervisor-resolution
 *       path and the model treated the slug as a separate person.</li>
 *   <li>Any rows tagged with the Oman-Demo email domain — covers intermediate
 *       revisions that used a wrong EMP-XXX range so we don't accumulate orphans
 *       when the range moves.</li>
 * </ul>
 *
 * <p>Re-seeded users now use {@code EMP-XXX} usernames at a non-conflicting offset;
 * this seeder makes the cleanup idempotent so re-running the seed profile on a
 * working DB does not leave stale rows behind.
 *
 * <p>FK detach order matters: clear the join + cache columns before deleting the User
 * row, since {@code supervisor_user_id} is a soft FK (no DB-level ON DELETE) but the
 * ORM still cannot save the next seeder's references if the legacy row is still
 * pointed at from {@code activity_supervisors}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(190)
public class OmanDemoLegacyUserCleanupSeeder implements CommandLineRunner {

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final ActivityRepository activityRepository;
  private final ActivitySupervisorRepository activitySupervisorRepository;
  private final DailyProgressReportRepository dailyProgressReportRepository;

  @Override
  @Transactional
  public void run(String... args) {
    // Always sweep orphan supervisor rows first. The FK is soft, so deleting a User
    // anywhere in the system can leave activity_supervisors rows that still display
    // the (now-deleted) name. The AI then has to fabricate an identity for that
    // user_id, which is exactly the problem we're trying to fix here.
    int orphans = 0;
    try {
      orphans = activitySupervisorRepository.deleteOrphanRows();
    } catch (Exception e) {
      log.warn("[oman-demo cleanup] orphan supervisor sweep failed: {}", e.getMessage());
    }

    List<User> legacy;
    try {
      legacy = userRepository.findLegacyOmanDemoUsers();
    } catch (Exception e) {
      log.warn("[oman-demo cleanup] could not query legacy users: {}", e.getMessage());
      return;
    }
    if (legacy.isEmpty()) {
      log.info("[oman-demo cleanup] no prior Oman-Demo users present "
          + "(orphan supervisor rows removed={})", orphans);
      return;
    }

    int removed = 0;
    int activitiesDetached = 0;
    int dprsDetached = 0;
    int joinsRemoved = 0;
    for (User u : legacy) {
      UUID uid = u.getId();
      try {
        joinsRemoved += (int) activitySupervisorRepository.deleteByUserId(uid);
      } catch (Exception e) {
        log.warn("[oman-demo cleanup] failed to clear activity_supervisors for {}: {}",
            u.getUsername(), e.getMessage());
      }
      try {
        activitiesDetached += activityRepository.detachSupervisor(uid);
      } catch (Exception e) {
        log.warn("[oman-demo cleanup] failed to detach activities for {}: {}",
            u.getUsername(), e.getMessage());
      }
      try {
        dprsDetached += dailyProgressReportRepository.detachSupervisor(uid);
      } catch (Exception e) {
        log.warn("[oman-demo cleanup] failed to detach DPRs for {}: {}",
            u.getUsername(), e.getMessage());
      }
      try {
        userRoleRepository.deleteByUserId(uid);
      } catch (Exception e) {
        log.warn("[oman-demo cleanup] failed to delete user_roles for {}: {}",
            u.getUsername(), e.getMessage());
      }
      try {
        userRepository.delete(u);
        removed++;
      } catch (Exception e) {
        log.warn("[oman-demo cleanup] could not delete user {}: {}",
            u.getUsername(), e.getMessage());
      }
    }

    log.info("[oman-demo cleanup] removed {} legacy users (activities detached={}, "
        + "DPRs detached={}, activity_supervisors rows removed={}, orphan rows removed={})",
        removed, activitiesDetached, dprsDetached, joinsRemoved, orphans);
  }
}
