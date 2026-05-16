package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot dev backfill: copies the legacy single-supervisor cache
 * {@code activities.supervisor_user_id} / {@code supervisor_user_name} into the new
 * {@code activity.activity_supervisors} join table so that activities created before
 * the multi-supervisor change continue to show the same supervisor on the UI.
 *
 * <p>Per-row idempotent — re-running it is cheap, and a partial backfill state (e.g. an
 * activity already moved to the multi-supervisor endpoint while peers still carry only the
 * legacy column) is correctly converged. For prod, Liquibase changeset 097 owns the SQL.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(50)
public class ActivitySupervisorBackfillSeeder {

  private final ActivityRepository activityRepository;
  private final ActivitySupervisorRepository activitySupervisorRepository;

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    int copied = 0;
    int alreadyPresent = 0;
    for (Activity a : activityRepository.findAll()) {
      if (a.getSupervisorUserId() == null) continue;
      if (activitySupervisorRepository.existsByActivityIdAndUserId(a.getId(), a.getSupervisorUserId())) {
        alreadyPresent++;
        continue;
      }
      ActivitySupervisor row = new ActivitySupervisor();
      row.setActivityId(a.getId());
      row.setUserId(a.getSupervisorUserId());
      row.setUserNameSnapshot(a.getSupervisorUserName());
      activitySupervisorRepository.save(row);
      copied++;
    }
    log.info("ActivitySupervisorBackfillSeeder: copied={}, alreadyPresent={}", copied, alreadyPresent);
  }
}
