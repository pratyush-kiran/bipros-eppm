package com.bipros.scheduling.application.service;

import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleStatus;
import com.bipros.scheduling.domain.model.SchedulingOption;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Saves a FAILED {@link ScheduleResult} audit row in its own independent transaction so the
 * record is committed even when the caller's transaction is rolling back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleFailureRecorder {

  private final ScheduleResultRepository scheduleResultRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(UUID projectId, SchedulingOption option, double durationSeconds, String message) {
    ScheduleResult failed = ScheduleResult.builder()
        .projectId(projectId)
        .dataDate(LocalDate.now())
        .calculatedAt(Instant.now())
        .schedulingOption(option != null ? option : SchedulingOption.RETAINED_LOGIC)
        .totalActivities(0)
        .criticalActivities(0)
        .durationSeconds(durationSeconds)
        .status(ScheduleStatus.FAILED)
        .build();
    scheduleResultRepository.save(failed);
    log.warn("Persisted FAILED schedule result for project={}, reason={}", projectId, message);
  }
}
