package com.bipros.scheduling.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.common.event.ScheduleRunRecordedEvent;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.scheduling.application.dto.FloatPathResponse;
import com.bipros.scheduling.application.dto.ScheduleActivityResultResponse;
import com.bipros.scheduling.application.dto.ScheduleResultResponse;
import com.bipros.scheduling.domain.algorithm.CPMScheduler;
import com.bipros.scheduling.domain.algorithm.CalendarCalculator;
import com.bipros.scheduling.domain.algorithm.MultipleFloatPathFinder;
import com.bipros.scheduling.domain.algorithm.SchedulableActivity;
import com.bipros.scheduling.domain.algorithm.SchedulableRelationship;
import com.bipros.scheduling.domain.algorithm.ScheduleData;
import com.bipros.scheduling.domain.algorithm.ScheduledActivity;
import com.bipros.scheduling.domain.model.ScheduleActivityResult;
import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleStatus;
import com.bipros.scheduling.domain.model.SchedulingOption;
import com.bipros.scheduling.domain.repository.ScheduleActivityResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SchedulingService {

  private final ScheduleResultRepository scheduleResultRepository;
  private final ScheduleActivityResultRepository scheduleActivityResultRepository;
  private final CalendarCalculator calendarCalculator;
  private final ActivityRepository activityRepository;
  private final ActivityRelationshipRepository activityRelationshipRepository;
  private final CalendarService calendarService;
  private final PertEstimateService pertEstimateService;
  private final ScheduleHealthService scheduleHealthService;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  public ScheduleResultResponse scheduleProject(UUID projectId, SchedulingOption option) {
    log.info("Scheduling project: id={}, option={}", projectId, option);
    long startTime = System.currentTimeMillis();

    try {
      // Load activities and relationships from database
      List<Activity> activities = activityRepository.findByProjectId(projectId);
      if (activities.isEmpty()) {
        throw new ResourceNotFoundException("Activities", projectId);
      }

      List<ActivityRelationship> relationships = activityRelationshipRepository.findByProjectId(projectId);

      // Determine default calendar - use first available activity calendar or any with valid ID
      UUID defaultCalendarId = activities.stream()
          .map(Activity::getCalendarId)
          .filter(calendarId -> calendarId != null)
          .findFirst()
          .orElseThrow(() -> new ResourceNotFoundException("Calendar", "No calendar assigned to activities for project: " + projectId));

      LocalDate dataDate = LocalDate.now();
      LocalDate projectStartDate = activities.stream()
          .map(Activity::getPlannedStartDate)
          .filter(date -> date != null)
          .min(LocalDate::compareTo)
          .orElse(LocalDate.now());

      // Map Activity entities to SchedulableActivity records
      Map<UUID, Activity> activityMap = new HashMap<>();
      List<SchedulableActivity> schedulableActivities = new ArrayList<>();

      // Fetch all PERT estimates for this project's activities
      List<UUID> activityIds = activities.stream().map(Activity::getId).toList();
      var pertEstimates = pertEstimateService.getByActivities(activityIds);
      var pertMap = pertEstimates.stream()
          .collect(java.util.stream.Collectors.toMap(
              pe -> pe.activityId(),
              pe -> pe
          ));

      if (!pertEstimates.isEmpty()) {
        log.debug("Found {} PERT estimates for project: id={}", pertEstimates.size(), projectId);
      }

      for (Activity activity : activities) {
        activityMap.put(activity.getId(), activity);

        // Use PERT expected duration if available, otherwise use remaining duration
        // (fall back to originalDuration when remainingDuration is null — e.g. NOT_STARTED activities)
        Double remDur = activity.getRemainingDuration();
        Double origDur = activity.getOriginalDuration();
        double durationToUse = remDur != null ? remDur : (origDur != null ? origDur : 0.0);
        if (pertMap.containsKey(activity.getId())) {
          durationToUse = pertMap.get(activity.getId()).expectedDuration();
        }

        SchedulableActivity schedulable = new SchedulableActivity(
            activity.getId(),
            activity.getOriginalDuration() != null ? activity.getOriginalDuration() : 0.0,
            durationToUse,
            activity.getCalendarId(),
            activity.getActivityType() != null ? activity.getActivityType().name() : null,
            activity.getStatus() != null ? activity.getStatus().name() : null,
            activity.getPercentComplete() != null ? activity.getPercentComplete() : 0.0,
            activity.getActualStartDate(),
            activity.getActualFinishDate(),
            activity.getPrimaryConstraintType() != null ? activity.getPrimaryConstraintType().name() : null,
            activity.getPrimaryConstraintDate(),
            activity.getSecondaryConstraintType() != null ? activity.getSecondaryConstraintType().name() : null,
            activity.getSecondaryConstraintDate()
        );
        schedulableActivities.add(schedulable);
      }

      // Map ActivityRelationship entities to SchedulableRelationship records
      List<SchedulableRelationship> schedulableRelationships = new ArrayList<>();
      for (ActivityRelationship rel : relationships) {
        SchedulableRelationship schedulable = new SchedulableRelationship(
            rel.getPredecessorActivityId(),
            rel.getSuccessorActivityId(),
            rel.getRelationshipType().name(),
            rel.getLag() != null ? rel.getLag() : 0.0
        );
        schedulableRelationships.add(schedulable);
      }

      // Build WBS_SUMMARY → children map for hammock/summary activity support
      Map<UUID, List<UUID>> summaryChildren = new HashMap<>();
      for (Activity activity : activities) {
        if (activity.getActivityType() != null
            && activity.getActivityType().name().equals("WBS_SUMMARY")
            && activity.getWbsNodeId() != null) {
          // Find all non-summary activities that share the same WBS node
          List<UUID> children = activities.stream()
              .filter(a -> activity.getWbsNodeId().equals(a.getWbsNodeId())
                  && !a.getId().equals(activity.getId())
                  && (a.getActivityType() == null || !a.getActivityType().name().equals("WBS_SUMMARY")))
              .map(Activity::getId)
              .toList();
          if (!children.isEmpty()) {
            summaryChildren.put(activity.getId(), children);
          }
        }
      }

      // Create schedule data
      ScheduleData scheduleData = new ScheduleData(
          projectId,
          dataDate,
          projectStartDate,
          null,
          schedulableActivities,
          schedulableRelationships,
          option != null ? option : SchedulingOption.RETAINED_LOGIC,
          summaryChildren
      );

      // Run CPM scheduler
      CPMScheduler scheduler = new CPMScheduler(calendarCalculator, defaultCalendarId);
      CPMScheduler.ScheduleOutput output = scheduler.scheduleWithWarnings(scheduleData);
      List<ScheduledActivity> scheduledActivities = output.activities();
      List<String> scheduleWarnings = output.warnings();
      CPMScheduler.StatusBreakdown statusBreakdown = output.statusBreakdown();

      // Calculate project statistics
      LocalDate projectFinish = scheduledActivities.stream()
          .map(ScheduledActivity::getEarlyFinish)
          .max(LocalDate::compareTo)
          .orElse(projectStartDate);

      int criticalCount = (int) scheduledActivities.stream()
          .filter(ScheduledActivity::isCritical)
          .count();

      double criticalPathLength = scheduledActivities.stream()
          .filter(ScheduledActivity::isCritical)
          .mapToDouble(ScheduledActivity::getRemainingDuration)
          .sum();

      // Save ScheduleResult
      ScheduleResult scheduleResult = ScheduleResult.builder()
          .projectId(projectId)
          .dataDate(dataDate)
          .projectStartDate(projectStartDate)
          .projectFinishDate(projectFinish)
          .criticalPathLength(criticalPathLength)
          .totalActivities(scheduledActivities.size())
          .criticalActivities(criticalCount)
          .schedulingOption(option != null ? option : SchedulingOption.RETAINED_LOGIC)
          .calculatedAt(Instant.now())
          .durationSeconds((double) (System.currentTimeMillis() - startTime) / 1000)
          .status(ScheduleStatus.COMPLETED)
          .build();

      ScheduleResult saved = scheduleResultRepository.save(scheduleResult);
      auditService.logCreate("ScheduleResult", saved.getId(), ScheduleResultResponse.from(saved));

      SchedulingOption runOption = saved.getSchedulingOption();
      eventPublisher.publishEvent(
          new ScheduleRunRecordedEvent(saved.getProjectId(), saved.getId(),
              runOption != null ? runOption.name() : null)
      );

      // Save activity results and update Activity entities. LOCKED activities still
      // get a ScheduleActivityResult row (so the CPM output is preserved for reports),
      // but their Activity entity is NOT mutated — the lock guard rejects cross-module
      // writes to the activity itself.
      List<ScheduleActivityResult> activityResults = new ArrayList<>();
      List<Activity> activitiesToSave = new ArrayList<>();
      int skippedLocked = 0;
      for (ScheduledActivity scheduled : scheduledActivities) {
        // Save schedule result
        ScheduleActivityResult activityResult = ScheduleActivityResult.builder()
            .scheduleResultId(saved.getId())
            .activityId(scheduled.getActivityId())
            .earlyStart(scheduled.getEarlyStart())
            .earlyFinish(scheduled.getEarlyFinish())
            .lateStart(scheduled.getLateStart())
            .lateFinish(scheduled.getLateFinish())
            .totalFloat(scheduled.getTotalFloat())
            .freeFloat(scheduled.getFreeFloat())
            .isCritical(scheduled.isCritical())
            .remainingDuration(scheduled.getRemainingDuration())
            .build();
        activityResults.add(activityResult);

        // Update Activity entity with calculated values
        Activity activity = activityMap.get(scheduled.getActivityId());
        if (activity != null) {
          if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
            skippedLocked++;
            continue;
          }
          activity.setEarlyStartDate(scheduled.getEarlyStart());
          activity.setEarlyFinishDate(scheduled.getEarlyFinish());
          activity.setLateStartDate(scheduled.getLateStart());
          activity.setLateFinishDate(scheduled.getLateFinish());
          activity.setTotalFloat(scheduled.getTotalFloat());
          activity.setFreeFloat(scheduled.getFreeFloat());
          activity.setIsCritical(scheduled.isCritical());

          // Set planned dates from calculated schedule so Gantt and other views can render
          if (activity.getPlannedStartDate() == null) {
            activity.setPlannedStartDate(scheduled.getEarlyStart());
          }
          if (activity.getPlannedFinishDate() == null) {
            activity.setPlannedFinishDate(scheduled.getEarlyFinish());
          }
          activitiesToSave.add(activity);
        }
      }
      scheduleActivityResultRepository.saveAll(activityResults);

      // Save updated Activity entities (LOCKED ones excluded)
      activityRepository.saveAll(activitiesToSave);

      if (skippedLocked > 0) {
        log.info("Skipped {} LOCKED activities", skippedLocked);
      }

      // Calculate schedule health index
      scheduleHealthService.calculateHealth(saved.getId());

      log.info("Project scheduled successfully: id={}, duration={}s, warnings={}",
          projectId, saved.getDurationSeconds(), scheduleWarnings.size());
      return ScheduleResultResponse.from(
          saved,
          scheduleWarnings,
          statusBreakdown.notStarted(),
          statusBreakdown.inProgress(),
          statusBreakdown.completed());

    } catch (Exception e) {
      log.error("Error scheduling project: id={}", projectId, e);
      throw e;
    }
  }

  public ScheduleResultResponse getLatestSchedule(UUID projectId) {
    log.debug("Fetching latest schedule for project: id={}", projectId);

    return scheduleResultRepository.findTopByProjectIdOrderByCalculatedAtDesc(projectId)
        .map(ScheduleResultResponse::from)
        .orElseGet(() -> scheduleProject(projectId, SchedulingOption.RETAINED_LOGIC));
  }

  public List<ScheduleActivityResultResponse> getCriticalPath(UUID projectId) {
    log.debug("Fetching critical path for project: id={}", projectId);

    ScheduleResult latestSchedule = ensureSchedule(projectId);

    return scheduleActivityResultRepository.findByScheduleResultIdAndIsCritical(latestSchedule.getId(), true)
        .stream()
        .map(ScheduleActivityResultResponse::from)
        .toList();
  }

  public List<FloatPathResponse> getFloatPaths(UUID projectId) {
    log.debug("Fetching float paths for project: id={}", projectId);

    ScheduleResult latestSchedule = ensureSchedule(projectId);

    List<ScheduleActivityResult> activityResults = scheduleActivityResultRepository
        .findByScheduleResultId(latestSchedule.getId());

    // Convert to scheduled activities for float path finder
    List<ScheduledActivity> scheduledActivities = new ArrayList<>();
    Map<UUID, List<SchedulableRelationship>> adjacency = new HashMap<>();

    for (ScheduleActivityResult result : activityResults) {
      ScheduledActivity scheduled = new ScheduledActivity(result.getActivityId(), result.getRemainingDuration());
      scheduled.setEarlyStart(result.getEarlyStart());
      scheduled.setEarlyFinish(result.getEarlyFinish());
      scheduled.setLateStart(result.getLateStart());
      scheduled.setLateFinish(result.getLateFinish());
      scheduled.setTotalFloat(result.getTotalFloat());
      scheduled.setFreeFloat(result.getFreeFloat());
      scheduled.setCritical(result.getIsCritical());
      scheduledActivities.add(scheduled);
    }

    MultipleFloatPathFinder pathFinder = new MultipleFloatPathFinder();
    return pathFinder.findFloatPaths(scheduledActivities, adjacency)
        .stream()
        .map(FloatPathResponse::from)
        .toList();
  }

  public List<ScheduleActivityResultResponse> getAllScheduledActivities(UUID projectId) {
    log.debug("Fetching all scheduled activities for project: id={}", projectId);

    ScheduleResult latestSchedule = ensureSchedule(projectId);

    return scheduleActivityResultRepository.findByScheduleResultId(latestSchedule.getId())
        .stream()
        .map(ScheduleActivityResultResponse::from)
        .toList();
  }

  /**
   * Return the latest schedule for the project, running the scheduler on demand when none exists
   * yet. Keeps GET endpoints useful for freshly-imported projects where nobody has clicked
   * "Schedule" yet.
   */
  private ScheduleResult ensureSchedule(UUID projectId) {
    return scheduleResultRepository.findTopByProjectIdOrderByCalculatedAtDesc(projectId)
        .orElseGet(() -> {
          scheduleProject(projectId, SchedulingOption.RETAINED_LOGIC);
          return scheduleResultRepository.findTopByProjectIdOrderByCalculatedAtDesc(projectId)
              .orElseThrow(() -> new ResourceNotFoundException("ScheduleResult", projectId));
        });
  }
}
