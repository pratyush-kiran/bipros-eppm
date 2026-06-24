package com.bipros.scheduling.domain.algorithm;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.scheduling.domain.model.SchedulingOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class CPMScheduler {

  private final CalendarCalculator calendarCalculator;
  private final UUID defaultCalendarId;

  /**
   * Scheduling result containing scheduled activities, any warnings generated, and a
   * status breakdown of the activities that were scheduled.
   */
  public record ScheduleOutput(
      List<ScheduledActivity> activities,
      List<String> warnings,
      StatusBreakdown statusBreakdown
  ) {}

  /** Activity-status breakdown surfaced in the scheduling log. */
  public record StatusBreakdown(int notStarted, int inProgress, int completed) {
    public static StatusBreakdown empty() {
      return new StatusBreakdown(0, 0, 0);
    }
  }

  public List<ScheduledActivity> schedule(ScheduleData data) {
    return scheduleWithWarnings(data).activities();
  }

  public ScheduleOutput scheduleWithWarnings(ScheduleData data) {
    log.debug(
        "Starting CPM scheduling for project: id={}, dataDate={}, option={}",
        data.projectId(),
        data.dataDate(),
        data.schedulingOption());

    List<String> warnings = new ArrayList<>();

    // Build activity index
    Map<UUID, SchedulableActivity> activityMap = new HashMap<>();
    for (SchedulableActivity activity : data.activities()) {
      activityMap.put(activity.id(), activity);
    }

    // Identify WBS_SUMMARY / hammock activities and their children
    Map<UUID, List<UUID>> summaryChildren = data.summaryChildren() != null
        ? data.summaryChildren() : Map.of();
    Set<UUID> summaryActivityIds = new HashSet<>();
    for (SchedulableActivity activity : data.activities()) {
      if ("WBS_SUMMARY".equals(activity.activityType())) {
        summaryActivityIds.add(activity.id());
      }
    }

    // Build adjacency lists for predecessors and successors
    Map<UUID, List<SchedulableRelationship>> predecessorMap = new HashMap<>();
    Map<UUID, List<SchedulableRelationship>> successorMap = new HashMap<>();
    Map<UUID, Integer> inDegree = new HashMap<>();

    for (SchedulableActivity activity : data.activities()) {
      predecessorMap.putIfAbsent(activity.id(), new ArrayList<>());
      successorMap.putIfAbsent(activity.id(), new ArrayList<>());
      inDegree.putIfAbsent(activity.id(), 0);
    }

    for (SchedulableRelationship rel : data.relationships()) {
      predecessorMap.computeIfAbsent(rel.successorId(), k -> new ArrayList<>()).add(rel);
      successorMap.computeIfAbsent(rel.predecessorId(), k -> new ArrayList<>()).add(rel);
      inDegree.put(rel.successorId(), inDegree.getOrDefault(rel.successorId(), 0) + 1);
    }

    // Warn about activities with no predecessors (except project start activities)
    for (SchedulableActivity activity : data.activities()) {
      if (predecessorMap.getOrDefault(activity.id(), List.of()).isEmpty()
          && successorMap.getOrDefault(activity.id(), List.of()).isEmpty()
          && !summaryActivityIds.contains(activity.id())) {
        warnings.add("Activity " + activity.id() + " has no predecessors or successors");
      }
    }

    // Validate that every relationship references activities that actually exist
    Set<UUID> activityIds = new HashSet<>();
    for (SchedulableActivity a : data.activities()) {
      activityIds.add(a.id());
    }
    for (SchedulableRelationship rel : data.relationships()) {
      if (!activityIds.contains(rel.predecessorId()) || !activityIds.contains(rel.successorId())) {
        throw new BusinessRuleException(
            "DANGLING_RELATIONSHIP",
            "Relationship references a missing activity (predecessor=" + rel.predecessorId()
            + ", successor=" + rel.successorId() + "). Remove the stale dependency and try again.");
      }
    }

    // Topological sort (Kahn's algorithm) to detect circular dependencies
    List<UUID> topologicalOrder = topologicalSort(data.activities(), inDegree, successorMap);
    if (topologicalOrder.size() != data.activities().size()) {
      throw new BusinessRuleException("CIRCULAR_DEPENDENCY", "Circular dependency detected in activity relationships");
    }

    // Initialize scheduled activities
    Map<UUID, ScheduledActivity> scheduledActivities = new HashMap<>();
    for (SchedulableActivity activity : data.activities()) {
      scheduledActivities.put(activity.id(), new ScheduledActivity(activity.id(), activity.remainingDuration()));
    }

    // Forward pass - calculate early start and early finish
    log.debug("Starting forward pass");
    forwardPass(data, topologicalOrder, activityMap, scheduledActivities, predecessorMap, warnings);

    // Resolve WBS_SUMMARY / hammock activities: duration = span of children
    for (UUID summaryId : summaryActivityIds) {
      List<UUID> children = summaryChildren.getOrDefault(summaryId, List.of());
      if (children.isEmpty()) continue;
      LocalDate earliest = null;
      LocalDate latest = null;
      for (UUID childId : children) {
        ScheduledActivity child = scheduledActivities.get(childId);
        if (child == null || child.getEarlyStart() == null || child.getEarlyFinish() == null) continue;
        if (earliest == null || child.getEarlyStart().isBefore(earliest)) {
          earliest = child.getEarlyStart();
        }
        if (latest == null || child.getEarlyFinish().isAfter(latest)) {
          latest = child.getEarlyFinish();
        }
      }
      if (earliest != null && latest != null) {
        ScheduledActivity summary = scheduledActivities.get(summaryId);
        summary.setEarlyStart(earliest);
        summary.setEarlyFinish(latest);
        summary.setRemainingDuration(
            calendarCalculator.countWorkingDays(defaultCalendarId, earliest, latest));
      }
    }

    // Backward pass - calculate late start and late finish
    log.debug("Starting backward pass");
    backwardPass(data, topologicalOrder, activityMap, scheduledActivities, successorMap);

    // Resolve WBS_SUMMARY late dates from children
    for (UUID summaryId : summaryActivityIds) {
      List<UUID> children = summaryChildren.getOrDefault(summaryId, List.of());
      if (children.isEmpty()) continue;
      LocalDate earliestLate = null;
      LocalDate latestLate = null;
      for (UUID childId : children) {
        ScheduledActivity child = scheduledActivities.get(childId);
        if (child == null || child.getLateStart() == null || child.getLateFinish() == null) continue;
        if (earliestLate == null || child.getLateStart().isBefore(earliestLate)) {
          earliestLate = child.getLateStart();
        }
        if (latestLate == null || child.getLateFinish().isAfter(latestLate)) {
          latestLate = child.getLateFinish();
        }
      }
      if (earliestLate != null && latestLate != null) {
        ScheduledActivity summary = scheduledActivities.get(summaryId);
        summary.setLateStart(earliestLate);
        summary.setLateFinish(latestLate);
      }
    }

    // Apply AS_LATE_AS_POSSIBLE constraint: shift early dates to late dates
    for (SchedulableActivity activity : data.activities()) {
      if ("AS_LATE_AS_POSSIBLE".equals(activity.primaryConstraintType())) {
        ScheduledActivity scheduled = scheduledActivities.get(activity.id());
        scheduled.setEarlyStart(scheduled.getLateStart());
        scheduled.setEarlyFinish(scheduled.getLateFinish());
      }
    }

    // Calculate floats
    log.debug("Calculating floats");
    double minTotalFloat = Double.POSITIVE_INFINITY;
    for (SchedulableActivity activity : data.activities()) {
      ScheduledActivity scheduled = scheduledActivities.get(activity.id());
      double totalFloat = computeWorkingDayDelta(
          scheduled.getEarlyStart(), scheduled.getLateStart());
      scheduled.setTotalFloat(totalFloat);

      if (totalFloat < minTotalFloat) {
        minTotalFloat = totalFloat;
      }
      if (totalFloat < 0) {
        warnings.add("Activity " + activity.id() + " has negative float (" + totalFloat + " days)");
      }
    }

    // Mark critical path. Activities with totalFloat == minTotalFloat lie on the longest path
    // (in P6 terms the critical path is TF<=0, but if there are no zero-float activities we
    // still surface the longest path so `critical-path` is never empty when a schedule exists).
    final double criticalFloat = Math.max(minTotalFloat, 0.0);
    final double epsilon = 1e-6;
    for (ScheduledActivity scheduled : scheduledActivities.values()) {
      scheduled.setCritical(scheduled.getTotalFloat() <= criticalFloat + epsilon);
    }

    // Calculate free float for each activity
    for (SchedulableRelationship rel : data.relationships()) {
      ScheduledActivity successor = scheduledActivities.get(rel.successorId());
      ScheduledActivity predecessor = scheduledActivities.get(rel.predecessorId());

      if (rel.type().equals("FS")) {
        double freeFloat = computeWorkingDayDelta(
            predecessor.getEarlyFinish(), successor.getEarlyStart());
        successor.setFreeFloat(Math.min(successor.getFreeFloat(), freeFloat));
      }
    }

    // Finalize free float: activities with no successors get free float = total float
    for (ScheduledActivity scheduled : scheduledActivities.values()) {
      if (scheduled.getFreeFloat() == Double.MAX_VALUE) {
        scheduled.setFreeFloat(scheduled.getTotalFloat());
      }
    }

    StatusBreakdown breakdown = computeStatusBreakdown(data.activities());

    log.debug("CPM scheduling completed with {} warnings", warnings.size());
    return new ScheduleOutput(new ArrayList<>(scheduledActivities.values()), warnings, breakdown);
  }

  /**
   * Bucket activity statuses for the scheduling log. Anything not matching the standard
   * P6 statuses (NOT_STARTED / IN_PROGRESS / COMPLETED) falls into notStarted by default —
   * we never want to mis-claim an activity is done.
   */
  private StatusBreakdown computeStatusBreakdown(List<SchedulableActivity> activities) {
    int notStarted = 0;
    int inProgress = 0;
    int completed = 0;
    for (SchedulableActivity a : activities) {
      String status = a.status() == null ? "" : a.status();
      switch (status) {
        case "COMPLETED" -> completed++;
        case "IN_PROGRESS" -> inProgress++;
        default -> notStarted++;
      }
    }
    return new StatusBreakdown(notStarted, inProgress, completed);
  }

  private void forwardPass(
      ScheduleData data,
      List<UUID> topologicalOrder,
      Map<UUID, SchedulableActivity> activityMap,
      Map<UUID, ScheduledActivity> scheduledActivities,
      Map<UUID, List<SchedulableRelationship>> predecessorMap,
      List<String> warnings) {

    for (UUID activityId : topologicalOrder) {
      SchedulableActivity activity = activityMap.get(activityId);
      ScheduledActivity scheduled = scheduledActivities.get(activityId);

      // Skip WBS_SUMMARY — computed after forward pass from children
      if ("WBS_SUMMARY".equals(activity.activityType())) {
        scheduled.setEarlyStart(data.projectStartDate());
        scheduled.setEarlyFinish(data.projectStartDate());
        continue;
      }

      LocalDate earlyStart = data.projectStartDate();

      // Get contributions from predecessors
      for (SchedulableRelationship rel : predecessorMap.getOrDefault(activityId, List.of())) {
        ScheduledActivity predScheduled = scheduledActivities.get(rel.predecessorId());
        LocalDate contribution = calculateRelationshipContribution(rel, predScheduled, true);
        if (contribution.isAfter(earlyStart)) {
          earlyStart = contribution;
        }
      }

      // Apply primary constraint (skip ALAP — handled after backward pass)
      if (activity.primaryConstraintType() != null
          && activity.primaryConstraintDate() != null
          && !"AS_LATE_AS_POSSIBLE".equals(activity.primaryConstraintType())) {
        earlyStart = applyPrimaryConstraintForward(
            activity.primaryConstraintType(),
            activity.primaryConstraintDate(),
            activity.remainingDuration(),
            earlyStart);
      }

      // Handle actuals based on scheduling option
      if (data.schedulingOption() == SchedulingOption.RETAINED_LOGIC) {
        if (activity.actualFinishDate() != null) {
          scheduled.setEarlyStart(effectiveActualStart(activity));
          scheduled.setEarlyFinish(activity.actualFinishDate());
          continue;
        } else if (activity.actualStartDate() != null) {
          earlyStart = activity.actualStartDate();
        } else if (activity.status() != null && activity.status().equals("IN_PROGRESS")) {
          earlyStart = data.dataDate();
        }
      } else if (data.schedulingOption() == SchedulingOption.PROGRESS_OVERRIDE) {
        // PROGRESS_OVERRIDE: completed activities keep actuals; in-progress activities
        // ignore retained logic and schedule remaining work from data date forward,
        // disregarding predecessor relationships for the remaining portion.
        if (activity.actualFinishDate() != null) {
          scheduled.setEarlyStart(effectiveActualStart(activity));
          scheduled.setEarlyFinish(activity.actualFinishDate());
          continue;
        } else if (activity.actualStartDate() != null || "IN_PROGRESS".equals(activity.status())) {
          // In-progress: schedule remaining duration from data date, ignoring predecessors
          earlyStart = data.dataDate();
          warnings.add("Activity " + activityId + " scheduled from data date (progress override)");
        }
      } else if (data.schedulingOption() == SchedulingOption.ACTUAL_DATES) {
        // ACTUAL_DATES: use actual dates as-is, no recalculation
        if (activity.actualFinishDate() != null) {
          scheduled.setEarlyStart(effectiveActualStart(activity));
          scheduled.setEarlyFinish(activity.actualFinishDate());
          continue;
        } else if (activity.actualStartDate() != null) {
          earlyStart = activity.actualStartDate();
        }
      }

      scheduled.setEarlyStart(earlyStart);
      UUID calId = activity.calendarId() != null ? activity.calendarId() : defaultCalendarId;
      LocalDate earlyFinish = calendarCalculator.addWorkingDays(
          calId,
          earlyStart,
          activity.remainingDuration());
      scheduled.setEarlyFinish(earlyFinish);
    }
  }

  private void backwardPass(
      ScheduleData data,
      List<UUID> topologicalOrder,
      Map<UUID, SchedulableActivity> activityMap,
      Map<UUID, ScheduledActivity> scheduledActivities,
      Map<UUID, List<SchedulableRelationship>> successorMap) {

    LocalDate projectFinishDate = data.mustFinishByDate() != null ? data.mustFinishByDate() : data.projectStartDate();

    // Find the maximum early finish to determine project finish
    for (SchedulableActivity activity : data.activities()) {
      ScheduledActivity scheduled = scheduledActivities.get(activity.id());
      if (scheduled.getEarlyFinish().isAfter(projectFinishDate)) {
        projectFinishDate = scheduled.getEarlyFinish();
      }
    }

    // Backward pass in reverse topological order
    for (int i = topologicalOrder.size() - 1; i >= 0; i--) {
      UUID activityId = topologicalOrder.get(i);
      SchedulableActivity activity = activityMap.get(activityId);
      ScheduledActivity scheduled = scheduledActivities.get(activityId);

      LocalDate lateFinish = projectFinishDate;

      // Get contributions from successors
      for (SchedulableRelationship rel : successorMap.getOrDefault(activityId, List.of())) {
        ScheduledActivity succScheduled = scheduledActivities.get(rel.successorId());
        LocalDate contribution = calculateRelationshipContribution(rel, succScheduled, false);
        if (contribution.isBefore(lateFinish)) {
          lateFinish = contribution;
        }
      }

      // Apply secondary constraint
      if (activity.secondaryConstraintType() != null && activity.secondaryConstraintDate() != null) {
        lateFinish = applySecondaryConstraintBackward(
            activity.secondaryConstraintType(),
            activity.secondaryConstraintDate(),
            activity.remainingDuration(),
            lateFinish);
      }

      // Handle actuals
      if (data.schedulingOption() == SchedulingOption.RETAINED_LOGIC && activity.actualFinishDate() != null) {
        scheduled.setLateStart(effectiveActualStart(activity));
        scheduled.setLateFinish(activity.actualFinishDate());
        continue;
      }

      scheduled.setLateFinish(lateFinish);
      UUID calId = activity.calendarId() != null ? activity.calendarId() : defaultCalendarId;
      LocalDate lateStart = calendarCalculator.subtractWorkingDays(
          calId,
          lateFinish,
          activity.remainingDuration());
      scheduled.setLateStart(lateStart);
    }
  }

  /**
   * Returns a non-null effective actual start for {@code activity}. If {@code actualStartDate} is
   * present it is returned as-is. When only {@code actualFinishDate} is recorded (e.g. completed
   * via DPR without a logged start), the start is back-calculated from the finish using the
   * original duration so the bar has sensible width. Falls back to the finish date (zero-width
   * point) when duration is unavailable.
   */
  private LocalDate effectiveActualStart(SchedulableActivity activity) {
    if (activity.actualStartDate() != null) {
      return activity.actualStartDate();
    }
    // actualFinishDate must be non-null in all callers; back-calculate start from finish
    LocalDate finish = activity.actualFinishDate();
    double dur = activity.originalDuration() > 0 ? activity.originalDuration()
        : activity.remainingDuration();
    if (finish != null && dur > 0) {
      UUID calId = activity.calendarId() != null ? activity.calendarId() : defaultCalendarId;
      return calendarCalculator.subtractWorkingDays(calId, finish, dur);
    }
    return finish; // last resort: zero-width point, non-null in all callers
  }

  /**
   * Working-day distance from {@code from} to {@code to}. Positive if {@code from <= to}, negative
   * otherwise. {@code countWorkingDays} is half-open [start, end) so same-day returns 0.
   */
  private double computeWorkingDayDelta(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      return 0.0;
    }
    if (from.isEqual(to)) {
      return 0.0;
    }
    if (from.isBefore(to)) {
      return calendarCalculator.countWorkingDays(defaultCalendarId, from, to);
    }
    return -calendarCalculator.countWorkingDays(defaultCalendarId, to, from);
  }

  private LocalDate calculateRelationshipContribution(
      SchedulableRelationship rel,
      ScheduledActivity related,
      boolean isForward) {

    return switch (rel.type()) {
      case "FS" -> isForward
          ? calendarCalculator.addWorkingDays(defaultCalendarId, related.getEarlyFinish(), rel.lag())
          : calendarCalculator.subtractWorkingDays(defaultCalendarId, related.getLateStart(), rel.lag());
      case "FF" -> isForward
          ? calendarCalculator.addWorkingDays(defaultCalendarId, related.getEarlyFinish(), rel.lag())
          : calendarCalculator.subtractWorkingDays(defaultCalendarId, related.getLateFinish(), rel.lag());
      case "SS" -> isForward
          ? calendarCalculator.addWorkingDays(defaultCalendarId, related.getEarlyStart(), rel.lag())
          : calendarCalculator.subtractWorkingDays(defaultCalendarId, related.getLateStart(), rel.lag());
      case "SF" -> isForward
          ? calendarCalculator.addWorkingDays(defaultCalendarId, related.getEarlyStart(), rel.lag())
          : calendarCalculator.subtractWorkingDays(defaultCalendarId, related.getLateFinish(), rel.lag());
      default -> isForward ? related.getEarlyFinish() : related.getLateStart();
    };
  }

  private LocalDate applyPrimaryConstraintForward(
      String constraintType,
      LocalDate constraintDate,
      double duration,
      LocalDate currentEarlyStart) {

    return switch (constraintType) {
      case "START_ON_OR_AFTER" -> constraintDate.isAfter(currentEarlyStart) ? constraintDate : currentEarlyStart;
      case "START_ON" -> constraintDate;
      case "FINISH_ON_OR_AFTER" -> {
        LocalDate adjustedStart = calendarCalculator.subtractWorkingDays(defaultCalendarId, constraintDate, duration);
        yield adjustedStart.isAfter(currentEarlyStart) ? adjustedStart : currentEarlyStart;
      }
      default -> currentEarlyStart;
    };
  }

  private LocalDate applySecondaryConstraintBackward(
      String constraintType,
      LocalDate constraintDate,
      double duration,
      LocalDate currentLateFinish) {

    return switch (constraintType) {
      case "START_ON_OR_BEFORE" -> constraintDate.isBefore(currentLateFinish)
          ? calendarCalculator.addWorkingDays(defaultCalendarId, constraintDate, duration)
          : currentLateFinish;
      case "FINISH_ON_OR_BEFORE" -> constraintDate.isBefore(currentLateFinish) ? constraintDate : currentLateFinish;
      case "FINISH_ON" -> constraintDate;
      default -> currentLateFinish;
    };
  }

  private List<UUID> topologicalSort(
      List<SchedulableActivity> activities,
      Map<UUID, Integer> inDegree,
      Map<UUID, List<SchedulableRelationship>> successorMap) {
    Map<UUID, Integer> inDegreeCopy = new HashMap<>(inDegree);
    Queue<UUID> queue = new LinkedList<>();

    for (UUID activityId : inDegreeCopy.keySet()) {
      if (inDegreeCopy.get(activityId) == 0) {
        queue.add(activityId);
      }
    }

    List<UUID> sorted = new ArrayList<>();
    while (!queue.isEmpty()) {
      UUID activityId = queue.poll();
      sorted.add(activityId);

      // Process successors and decrease their in-degree
      for (SchedulableRelationship rel : successorMap.getOrDefault(activityId, List.of())) {
        UUID successorId = rel.successorId();
        inDegreeCopy.put(successorId, inDegreeCopy.get(successorId) - 1);
        if (inDegreeCopy.get(successorId) == 0) {
          queue.add(successorId);
        }
      }
    }

    return sorted;
  }
}
