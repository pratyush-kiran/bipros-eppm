package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.application.dto.ResourceLevelingResponse;
import com.bipros.resource.application.dto.UtilizationProfileEntry;
import com.bipros.resource.domain.algorithm.LevelingMode;
import com.bipros.resource.domain.algorithm.LevelingResult;
import com.bipros.resource.domain.algorithm.ResourceLeveler;
import com.bipros.resource.domain.algorithm.ResourceLeveler.ActivityInfo;
import com.bipros.resource.domain.algorithm.ResourceLeveler.AssignmentInfo;
import com.bipros.resource.domain.algorithm.ResourceLeveler.LevelingInput;
import com.bipros.resource.domain.algorithm.ResourceLeveler.LevelingOutput;
import com.bipros.resource.domain.algorithm.ResourceSmoother;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resource leveling operations.
 * Orchestrates resource leveling by loading data, running the algorithm, and updating dates.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ResourceLevelingService {

  private final ActivityRepository activityRepository;
  private final ResourceAssignmentRepository assignmentRepository;
  private final ResourceRepository resourceRepository;
  private final ProjectRepository projectRepository;

  /**
   * Performs resource leveling for all activities in a project.
   *
   * @param projectId The project ID
   * @return Leveling result with delays and metrics
   */
  public LevelingResult levelResources(UUID projectId) {
    log.info("Starting resource leveling for project: {}", projectId);

    // Load project
    Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

    // Load activities for the project
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    if (activities.isEmpty()) {
      log.warn("No activities found for project: {}", projectId);
      return new LevelingResult(Map.of(), List.of(), 0);
    }

    // Load resource assignments for the project
    List<ResourceAssignment> assignments = assignmentRepository.findByProjectId(projectId);

    // Build resource capacity map
    Set<UUID> resourceIds = assignments.stream()
        .map(ResourceAssignment::getResourceId)
        .collect(Collectors.toSet());

    Map<UUID, Double> resourceMaxUnits = new HashMap<>();
    for (UUID resourceId : resourceIds) {
      Resource resource = resourceRepository.findById(resourceId)
          .orElseThrow(() -> new ResourceNotFoundException("Resource", resourceId));
      resourceMaxUnits.put(resourceId, dailyCapacityFor(resource));
    }

    // Convert to algorithm input format
    List<ActivityInfo> activityInfos = activities.stream()
        .map(a -> new ActivityInfo(
            a.getId(),
            a.getEarlyStartDate(),
            a.getEarlyFinishDate(),
            a.getLateStartDate(),
            a.getLateFinishDate(),
            a.getTotalFloat(),
            project.getPriority(),
            a.getPlannedStartDate(),
            a.getPlannedFinishDate()))
        .filter(a -> a.currentStart() != null && a.currentFinish() != null)
        .collect(Collectors.toList());

    List<AssignmentInfo> assignmentInfos = assignments.stream()
        .map(a -> new AssignmentInfo(a.getActivityId(), a.getResourceId(), a.getPlannedUnits()))
        .filter(a -> a.plannedUnits() != null && a.plannedUnits() > 0)
        .collect(Collectors.toList());

    if (activityInfos.isEmpty() || assignmentInfos.isEmpty()) {
      log.warn("Insufficient activity or assignment data for leveling: project={}", projectId);
      return new LevelingResult(Map.of(), List.of(), 0);
    }

    // Run the leveling algorithm
    ResourceLeveler leveler = new ResourceLeveler();
    LevelingInput input = new LevelingInput(activityInfos, assignmentInfos, resourceMaxUnits);
    LevelingOutput output = leveler.level(input);

    log.info("Leveling completed: iterations={}, overallocations resolved={}",
        output.iterationsUsed(), output.overallocationsResolved().size());

    // Update activity dates with delays
    int skippedLocked = 0;
    for (Map.Entry<UUID, LocalDate> delay : output.delayedActivities().entrySet()) {
      UUID activityId = delay.getKey();
      LocalDate newStartDate = delay.getValue();

      Activity activity = activityRepository.findById(activityId)
          .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));

      if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        skippedLocked++;
        continue;
      }

      if (activity.getPlannedStartDate() != null) {
        long originalDuration = java.time.temporal.ChronoUnit.DAYS.between(
            activity.getPlannedStartDate(), activity.getPlannedFinishDate()) + 1;
        LocalDate newFinishDate = newStartDate.plusDays(originalDuration - 1);

        activity.setPlannedStartDate(newStartDate);
        activity.setPlannedFinishDate(newFinishDate);

        activityRepository.save(activity);

        log.info("Updated activity {} dates: start={}, finish={}",
            activityId, newStartDate, newFinishDate);
      }
    }
    if (skippedLocked > 0) {
      log.info("Skipped {} LOCKED activities", skippedLocked);
    }

    // Convert output to result
    LevelingResult result = new LevelingResult(
        output.delayedActivities(),
        output.overallocationsResolved(),
        output.iterationsUsed());

    log.info("Resource leveling completed successfully for project: {}", projectId);
    return result;
  }

  /**
   * Performs resource leveling with mode selection: LEVEL_ALL, LEVEL_WITHIN_FLOAT, or SMOOTH.
   * Persists the proposed activity-date shifts.
   */
  public ResourceLevelingResponse levelResourcesWithMode(UUID projectId, LevelingMode mode, List<UUID> resourceFilter) {
    return runLevelingWithMode(projectId, mode, resourceFilter, false);
  }

  /**
   * Preview-only counterpart of {@link #levelResourcesWithMode}. Runs the same algorithm
   * and returns the same shape of result, but does NOT write activity dates back to the DB.
   * Lets the UI show a "would do this" preview before the user clicks Apply.
   */
  @Transactional(readOnly = true)
  public ResourceLevelingResponse previewLevelingWithMode(UUID projectId, LevelingMode mode, List<UUID> resourceFilter) {
    return runLevelingWithMode(projectId, mode, resourceFilter, true);
  }

  private ResourceLevelingResponse runLevelingWithMode(UUID projectId, LevelingMode mode, List<UUID> resourceFilter, boolean dryRun) {
    log.info("Starting resource leveling for project: {}, mode: {}, dryRun: {}", projectId, mode, dryRun);

    Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

    List<Activity> activities = activityRepository.findByProjectId(projectId);
    if (activities.isEmpty()) {
      return new ResourceLevelingResponse(mode, 0, 0, 0.0, 0.0, List.of(), List.of("No activities found"));
    }

    List<ResourceAssignment> assignments = assignmentRepository.findByProjectId(projectId);

    // Filter by resource IDs if specified
    if (resourceFilter != null && !resourceFilter.isEmpty()) {
      Set<UUID> filterSet = new HashSet<>(resourceFilter);
      assignments = assignments.stream()
          .filter(a -> filterSet.contains(a.getResourceId()))
          .collect(Collectors.toList());
    }

    Map<UUID, Double> resourceMaxUnits = buildResourceCapacityMap(assignments);

    List<ActivityInfo> activityInfos = activities.stream()
        .map(a -> new ActivityInfo(
            a.getId(),
            a.getEarlyStartDate(),
            a.getEarlyFinishDate(),
            a.getLateStartDate(),
            a.getLateFinishDate(),
            a.getTotalFloat(),
            project.getPriority(),
            a.getPlannedStartDate(),
            a.getPlannedFinishDate()))
        .filter(a -> a.currentStart() != null && a.currentFinish() != null)
        .collect(Collectors.toList());

    List<AssignmentInfo> assignmentInfos = assignments.stream()
        .map(a -> new AssignmentInfo(a.getActivityId(), a.getResourceId(), a.getPlannedUnits()))
        .filter(a -> a.plannedUnits() != null && a.plannedUnits() > 0)
        .collect(Collectors.toList());

    if (activityInfos.isEmpty() || assignmentInfos.isEmpty()) {
      return new ResourceLevelingResponse(mode, 0, 0, 0.0, 0.0, List.of(),
          List.of("Insufficient activity or assignment data"));
    }

    // Calculate peak utilization before leveling
    double peakBefore = calculatePeakUtilization(activityInfos, assignmentInfos, resourceMaxUnits);

    // Store original starts for delta tracking
    Map<UUID, LocalDate> originalStarts = activityInfos.stream()
        .collect(Collectors.toMap(ActivityInfo::activityId, ActivityInfo::currentStart));

    return switch (mode) {
      case SMOOTH -> runSmoothing(activityInfos, assignmentInfos, resourceMaxUnits, originalStarts, peakBefore, activities, dryRun);
      case LEVEL_WITHIN_FLOAT -> runLevelingWithinFloat(activityInfos, assignmentInfos, resourceMaxUnits, originalStarts, peakBefore, activities, dryRun);
      case LEVEL_ALL -> runFullLeveling(activityInfos, assignmentInfos, resourceMaxUnits, originalStarts, peakBefore, activities, dryRun);
    };
  }

  /**
   * Returns daily utilization profile for all resources in a project.
   */
  @Transactional(readOnly = true)
  public List<UtilizationProfileEntry> getUtilizationProfile(UUID projectId) {
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    List<ResourceAssignment> assignments = assignmentRepository.findByProjectId(projectId);
    Map<UUID, Double> resourceMaxUnits = buildResourceCapacityMap(assignments);

    // Build resource name map
    Map<UUID, String> resourceNames = new HashMap<>();
    for (UUID rid : resourceMaxUnits.keySet()) {
      resourceRepository.findById(rid).ifPresent(r -> resourceNames.put(rid, r.getName()));
    }

    // Calculate date range
    LocalDate projectStart = activities.stream()
        .map(Activity::getPlannedStartDate)
        .filter(Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
    LocalDate projectEnd = activities.stream()
        .map(Activity::getPlannedFinishDate)
        .filter(Objects::nonNull)
        .max(LocalDate::compareTo)
        .orElse(null);

    if (projectStart == null || projectEnd == null) {
      return List.of();
    }

    // Build activity map for quick lookup
    Map<UUID, Activity> activityMap = activities.stream()
        .collect(Collectors.toMap(Activity::getId, a -> a));

    List<UtilizationProfileEntry> entries = new ArrayList<>();

    for (LocalDate date = projectStart; !date.isAfter(projectEnd); date = date.plusDays(1)) {
      Map<UUID, Double> dailyDemand = new HashMap<>();

      for (ResourceAssignment assignment : assignments) {
        Activity activity = activityMap.get(assignment.getActivityId());
        if (activity == null || activity.getPlannedStartDate() == null || activity.getPlannedFinishDate() == null) {
          continue;
        }

        if (!activity.getPlannedStartDate().isAfter(date) && !activity.getPlannedFinishDate().isBefore(date)) {
          long duration = ChronoUnit.DAYS.between(activity.getPlannedStartDate(), activity.getPlannedFinishDate()) + 1;
          if (duration > 0 && assignment.getPlannedUnits() != null) {
            double unitsPerDay = assignment.getPlannedUnits() / duration;
            dailyDemand.merge(assignment.getResourceId(), unitsPerDay, Double::sum);
          }
        }
      }

      for (var entry : dailyDemand.entrySet()) {
        UUID resourceId = entry.getKey();
        double demand = entry.getValue();
        double capacity = resourceMaxUnits.getOrDefault(resourceId, 8.0);
        double utilization = capacity > 0 ? demand / capacity : 0.0;

        entries.add(new UtilizationProfileEntry(
            date, resourceId, resourceNames.getOrDefault(resourceId, "Unknown"),
            Math.round(demand * 100.0) / 100.0,
            capacity,
            Math.round(utilization * 10000.0) / 10000.0));
      }
    }

    return entries;
  }

  /**
   * Daily capacity for leveling. The new Resource entity has no {@code maxUnitsPerDay}; we read
   * {@code availability} (a percentage 0–100) and convert to an 8-hour-day equivalent, defaulting
   * to 8.0 when no availability is recorded.
   */
  private static double dailyCapacityFor(Resource r) {
    if (r.getAvailability() != null) {
      double pct = r.getAvailability().doubleValue();
      if (pct > 0.0) return pct / 100.0 * 8.0;
    }
    return 8.0;
  }

  private Map<UUID, Double> buildResourceCapacityMap(List<ResourceAssignment> assignments) {
    Set<UUID> resourceIds = assignments.stream()
        .map(ResourceAssignment::getResourceId)
        .collect(Collectors.toSet());

    Map<UUID, Double> resourceMaxUnits = new HashMap<>();
    for (UUID resourceId : resourceIds) {
      resourceRepository.findById(resourceId)
          .ifPresent(r -> resourceMaxUnits.put(resourceId, dailyCapacityFor(r)));
    }
    return resourceMaxUnits;
  }

  private double calculatePeakUtilization(List<ActivityInfo> activityInfos,
      List<AssignmentInfo> assignmentInfos, Map<UUID, Double> resourceMaxUnits) {
    Map<UUID, ActivityInfo> actMap = activityInfos.stream()
        .collect(Collectors.toMap(ActivityInfo::activityId, a -> a));

    LocalDate start = activityInfos.stream().map(ActivityInfo::currentStart).min(LocalDate::compareTo).orElse(null);
    LocalDate end = activityInfos.stream().map(ActivityInfo::currentFinish).max(LocalDate::compareTo).orElse(null);
    if (start == null || end == null) return 0.0;

    double maxUtil = 0.0;
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      Map<UUID, Double> dailyDemand = new HashMap<>();
      for (var assignment : assignmentInfos) {
        var activity = actMap.get(assignment.activityId());
        if (activity == null) continue;
        if (!activity.currentStart().isAfter(date) && !activity.currentFinish().isBefore(date)) {
          long dur = activity.getDuration();
          if (dur > 0) {
            dailyDemand.merge(assignment.resourceId(), assignment.plannedUnits() / dur, Double::sum);
          }
        }
      }
      for (var entry : dailyDemand.entrySet()) {
        double capacity = resourceMaxUnits.getOrDefault(entry.getKey(), 8.0);
        if (capacity > 0) {
          maxUtil = Math.max(maxUtil, entry.getValue() / capacity);
        }
      }
    }
    return Math.round(maxUtil * 10000.0) / 10000.0;
  }

  private ResourceLevelingResponse runSmoothing(List<ActivityInfo> activityInfos,
      List<AssignmentInfo> assignmentInfos, Map<UUID, Double> resourceMaxUnits,
      Map<UUID, LocalDate> originalStarts, double peakBefore, List<Activity> activities, boolean dryRun) {

    ResourceSmoother smoother = new ResourceSmoother();
    ResourceSmoother.SmoothingResult result = smoother.smooth(activityInfos, assignmentInfos, resourceMaxUnits);

    // Apply shifted dates to activities (skipped on dryRun — preview path)
    List<ResourceLevelingResponse.ShiftedActivity> shifted = new ArrayList<>();
    int skippedLocked = 0;
    for (var entry : result.shiftedActivities().entrySet()) {
      UUID activityId = entry.getKey();
      LocalDate newStart = entry.getValue();
      LocalDate origStart = originalStarts.get(activityId);

      Activity activity = activities.stream().filter(a -> a.getId().equals(activityId)).findFirst().orElse(null);
      if (activity != null && !dryRun && activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        skippedLocked++;
        continue;
      }
      if (activity != null && activity.getPlannedStartDate() != null && !dryRun) {
        long duration = ChronoUnit.DAYS.between(activity.getPlannedStartDate(), activity.getPlannedFinishDate()) + 1;
        activity.setPlannedStartDate(newStart);
        activity.setPlannedFinishDate(newStart.plusDays(duration - 1));
        activityRepository.save(activity);
      }

      long delayDays = origStart != null ? ChronoUnit.DAYS.between(origStart, newStart) : 0;
      shifted.add(new ResourceLevelingResponse.ShiftedActivity(activityId, origStart, newStart, delayDays));
    }
    if (skippedLocked > 0) {
      log.info("Skipped {} LOCKED activities", skippedLocked);
    }

    return new ResourceLevelingResponse(
        LevelingMode.SMOOTH,
        shifted.size(),
        result.iterationsUsed(),
        result.peakBefore(),
        result.peakAfter(),
        shifted,
        List.of("Smoothing completed: peak reduced from " +
            String.format("%.2f", result.peakBefore()) + " to " + String.format("%.2f", result.peakAfter())));
  }

  private ResourceLevelingResponse runLevelingWithinFloat(List<ActivityInfo> activityInfos,
      List<AssignmentInfo> assignmentInfos, Map<UUID, Double> resourceMaxUnits,
      Map<UUID, LocalDate> originalStarts, double peakBefore, List<Activity> activities, boolean dryRun) {

    // Filter to only activities with float > 0
    List<ActivityInfo> floatActivities = activityInfos.stream()
        .filter(a -> a.totalFloat() != null && a.totalFloat() > 0)
        .collect(Collectors.toList());

    // Also include zero-float activities as fixed anchors
    List<ActivityInfo> allForLeveling = new ArrayList<>(activityInfos);

    ResourceLeveler leveler = new ResourceLeveler();
    LevelingInput input = new LevelingInput(allForLeveling, assignmentInfos, resourceMaxUnits);
    LevelingOutput output = leveler.level(input);

    // Only apply shifts that stay within original float
    List<ResourceLevelingResponse.ShiftedActivity> shifted = new ArrayList<>();
    int skippedLocked = 0;
    for (var entry : output.delayedActivities().entrySet()) {
      UUID activityId = entry.getKey();
      LocalDate newStart = entry.getValue();
      LocalDate origStart = originalStarts.get(activityId);

      // Check if shift is within float
      ActivityInfo origInfo = activityInfos.stream()
          .filter(a -> a.activityId().equals(activityId)).findFirst().orElse(null);
      if (origInfo != null && origInfo.lateStart() != null && newStart.isAfter(origInfo.lateStart())) {
        continue; // Skip — exceeds float
      }

      Activity activity = activities.stream().filter(a -> a.getId().equals(activityId)).findFirst().orElse(null);
      if (activity != null && !dryRun && activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        skippedLocked++;
        continue;
      }
      if (activity != null && activity.getPlannedStartDate() != null && !dryRun) {
        long duration = ChronoUnit.DAYS.between(activity.getPlannedStartDate(), activity.getPlannedFinishDate()) + 1;
        activity.setPlannedStartDate(newStart);
        activity.setPlannedFinishDate(newStart.plusDays(duration - 1));
        activityRepository.save(activity);
      }

      long delayDays = origStart != null ? ChronoUnit.DAYS.between(origStart, newStart) : 0;
      shifted.add(new ResourceLevelingResponse.ShiftedActivity(activityId, origStart, newStart, delayDays));
    }
    if (skippedLocked > 0) {
      log.info("Skipped {} LOCKED activities", skippedLocked);
    }

    double peakAfter = calculatePeakUtilization(activityInfos, assignmentInfos, resourceMaxUnits);

    return new ResourceLevelingResponse(
        LevelingMode.LEVEL_WITHIN_FLOAT,
        shifted.size(),
        output.iterationsUsed(),
        peakBefore,
        peakAfter,
        shifted,
        output.overallocationsResolved());
  }

  private ResourceLevelingResponse runFullLeveling(List<ActivityInfo> activityInfos,
      List<AssignmentInfo> assignmentInfos, Map<UUID, Double> resourceMaxUnits,
      Map<UUID, LocalDate> originalStarts, double peakBefore, List<Activity> activities, boolean dryRun) {

    ResourceLeveler leveler = new ResourceLeveler();
    LevelingInput input = new LevelingInput(activityInfos, assignmentInfos, resourceMaxUnits);
    LevelingOutput output = leveler.level(input);

    List<ResourceLevelingResponse.ShiftedActivity> shifted = new ArrayList<>();
    int skippedLocked = 0;
    for (var entry : output.delayedActivities().entrySet()) {
      UUID activityId = entry.getKey();
      LocalDate newStart = entry.getValue();
      LocalDate origStart = originalStarts.get(activityId);

      Activity activity = activities.stream().filter(a -> a.getId().equals(activityId)).findFirst().orElse(null);
      if (activity != null && !dryRun && activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        skippedLocked++;
        continue;
      }
      if (activity != null && activity.getPlannedStartDate() != null && !dryRun) {
        long duration = ChronoUnit.DAYS.between(activity.getPlannedStartDate(), activity.getPlannedFinishDate()) + 1;
        activity.setPlannedStartDate(newStart);
        activity.setPlannedFinishDate(newStart.plusDays(duration - 1));
        activityRepository.save(activity);
      }

      long delayDays = origStart != null ? ChronoUnit.DAYS.between(origStart, newStart) : 0;
      shifted.add(new ResourceLevelingResponse.ShiftedActivity(activityId, origStart, newStart, delayDays));
    }
    if (skippedLocked > 0) {
      log.info("Skipped {} LOCKED activities", skippedLocked);
    }

    double peakAfter = calculatePeakUtilization(activityInfos, assignmentInfos, resourceMaxUnits);

    return new ResourceLevelingResponse(
        LevelingMode.LEVEL_ALL,
        shifted.size(),
        output.iterationsUsed(),
        peakBefore,
        peakAfter,
        shifted,
        output.overallocationsResolved());
  }
}
