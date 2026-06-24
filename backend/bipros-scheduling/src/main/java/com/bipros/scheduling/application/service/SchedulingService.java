package com.bipros.scheduling.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.common.event.ScheduleRunRecordedEvent;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
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
import com.bipros.scheduling.infrastructure.adapter.SnapshotCalendarCalculator;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
  private final ProjectRepository projectRepository;
  private final CalendarRepository calendarRepository;
  private final ScheduleFailureRecorder failureRecorder;

  // Per-project lock to serialize concurrent schedule runs within a single instance.
  // NOTE: a distributed lock (e.g. Redis SETNX) would be required for multi-node deployments.
  private final ConcurrentHashMap<UUID, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

  public ScheduleResultResponse scheduleProject(UUID projectId, SchedulingOption option) {
    log.info("Scheduling project: id={}, option={}", projectId, option);
    long startTime = System.currentTimeMillis();

    ReentrantLock lock = projectLocks.computeIfAbsent(projectId, k -> new ReentrantLock());
    lock.lock();
    try {
      // Load activities and relationships from database
      List<Activity> activities = activityRepository.findByProjectId(projectId);
      if (activities.isEmpty()) {
        throw new ResourceNotFoundException("Activities", projectId);
      }

      List<ActivityRelationship> relationships = activityRelationshipRepository.findByProjectId(projectId);

      // Determine default calendar via fallback chain: activity → project → project-default → global-default
      UUID defaultCalendarId = resolveDefaultCalendarId(projectId, activities);

      // Load the project once to honour its schedule metadata (data date, planned start, must-finish-by).
      Project project = projectRepository.findById(projectId).orElse(null);

      // Use the project's data date for reproducible schedule runs; fall back to today only when unset.
      LocalDate dataDate = (project != null && project.getDataDate() != null)
          ? project.getDataDate()
          : LocalDate.now();

      // Prefer the project's own planned start; else derive from the earliest activity planned start.
      LocalDate projectStartDate = (project != null && project.getPlannedStartDate() != null)
          ? project.getPlannedStartDate()
          : activities.stream()
              .map(Activity::getPlannedStartDate)
              .filter(date -> date != null)
              .min(LocalDate::compareTo)
              .orElse(LocalDate.now());

      // Thread the contractual deadline into the backward pass (null = unconstrained).
      // NOTE: The CPM engine currently anchors project finish to max(mustFinishByDate, maxEF),
      // so a deadline earlier than the computed finish produces zero/positive float rather than
      // negative float. Making an earlier deadline drive negative float requires a separate
      // engine-semantics change in CPMScheduler and is deferred as a follow-up.
      LocalDate mustFinishByDate = (project != null) ? project.getMustFinishByDate() : null;

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
            rel.getRelationshipType().code(),
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
          mustFinishByDate,
          schedulableActivities,
          schedulableRelationships,
          option != null ? option : SchedulingOption.RETAINED_LOGIC,
          summaryChildren
      );

      // Build a snapshot-backed calculator for this run.
      // The window is intentionally generous (±1 year back, +10 years forward) so the
      // engine never queries a date outside the snapshot's exception coverage. Construction
      // schedules are far shorter in practice; the extra range has no performance cost
      // because exceptions outside the range simply aren't loaded.
      LocalDate windowFrom = (projectStartDate.isBefore(dataDate) ? projectStartDate : dataDate)
          .minusYears(1);
      LocalDate windowTo = projectStartDate.plusYears(10);
      CalendarCalculator runCalculator =
          new SnapshotCalendarCalculator(calendarService, windowFrom, windowTo);

      // Run CPM scheduler
      CPMScheduler scheduler = new CPMScheduler(runCalculator, defaultCalendarId);
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

      // Replace raw UUIDs in warnings with human-readable "code - name" labels
      Map<UUID, String> labelById = activities.stream()
          .collect(Collectors.toMap(Activity::getId, SchedulingService::activityLabel));
      List<String> readableWarnings = toReadableWarnings(scheduleWarnings, labelById);

      log.info("Project scheduled successfully: id={}, duration={}s, warnings={}",
          projectId, saved.getDurationSeconds(), readableWarnings.size());
      return ScheduleResultResponse.from(
          saved,
          readableWarnings,
          statusBreakdown.notStarted(),
          statusBreakdown.inProgress(),
          statusBreakdown.completed());

    } catch (Exception e) {
      log.error("Error scheduling project: id={}", projectId, e);
      double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
      // Best-effort audit write — never let a recorder failure mask the original scheduling error.
      try {
        failureRecorder.recordFailure(projectId, option, elapsed, e.getMessage());
      } catch (Exception recorderEx) {
        log.warn("Failed to persist FAILED schedule audit row for project={}", projectId, recorderEx);
      }
      throw e;
    } finally {
      lock.unlock();
    }
  }

  public ScheduleResultResponse getLatestSchedule(UUID projectId) {
    log.debug("Fetching latest schedule for project: id={}", projectId);

    return scheduleResultRepository.findTopByProjectIdAndStatusOrderByCalculatedAtDesc(projectId, ScheduleStatus.COMPLETED)
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

    List<ActivityRelationship> relationships = activityRelationshipRepository.findByProjectId(projectId);
    Map<UUID, List<SchedulableRelationship>> adjacency = new HashMap<>();
    for (ActivityRelationship rel : relationships) {
      SchedulableRelationship sr = new SchedulableRelationship(
          rel.getPredecessorActivityId(),
          rel.getSuccessorActivityId(),
          rel.getRelationshipType().code(),
          rel.getLag() != null ? rel.getLag() : 0.0);
      adjacency.computeIfAbsent(rel.getPredecessorActivityId(), k -> new ArrayList<>()).add(sr);
    }

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
   * Resolves the calendar to use for CPM scheduling via a four-step fallback chain:
   * <ol>
   *   <li>First non-null {@code Activity.calendarId} in the activity list (preserves current per-activity calendar behavior).</li>
   *   <li>The project's own {@code Project.calendarId} (set on Overview → Calendar).</li>
   *   <li>A project-scoped calendar from {@code CalendarRepository.findByProjectId}: prefers the one with {@code isDefault==true}, else the first in the list.</li>
   *   <li>The global-default calendar: {@code CalendarRepository.findByCalendarTypeAndIsDefaultTrue(GLOBAL)}.</li>
   * </ol>
   * For steps 2-4 the candidate id is validated against {@code calendarRepository.findById} before being returned, so dangling ids are skipped.
   * Throws {@link ResourceNotFoundException} with a clear message when no calendar can be resolved.
   */
  UUID resolveDefaultCalendarId(UUID projectId, List<Activity> activities) {
    // Step 1 — activity-level calendar
    UUID fromActivity = activities.stream()
        .map(Activity::getCalendarId)
        .filter(id -> id != null)
        .findFirst()
        .orElse(null);
    if (fromActivity != null) {
      return fromActivity;
    }

    // Step 2 — project-level calendar
    UUID fromProject = projectRepository.findById(projectId)
        .map(Project::getCalendarId)
        .orElse(null);
    if (fromProject != null && calendarRepository.findById(fromProject).isPresent()) {
      return fromProject;
    }

    // Step 3 — project-scoped default calendar
    List<Calendar> projectCalendars = calendarRepository.findByProjectId(projectId);
    if (!projectCalendars.isEmpty()) {
      UUID preferred = projectCalendars.stream()
          .filter(c -> Boolean.TRUE.equals(c.getIsDefault()))
          .map(Calendar::getId)
          .findFirst()
          .orElseGet(() -> projectCalendars.get(0).getId());
      if (calendarRepository.findById(preferred).isPresent()) {
        return preferred;
      }
    }

    // Step 4 — global default calendar
    UUID fromGlobal = calendarRepository
        .findByCalendarTypeAndIsDefaultTrue(CalendarType.GLOBAL)
        .map(Calendar::getId)
        .orElse(null);
    if (fromGlobal != null && calendarRepository.findById(fromGlobal).isPresent()) {
      return fromGlobal;
    }

    throw new ResourceNotFoundException("Calendar",
        "No calendar configured for this project — set one on the project (Overview → Calendar) or assign one to its activities.");
  }

  /**
   * Return the latest schedule for the project, running the scheduler on demand when none exists
   * yet. Keeps GET endpoints useful for freshly-imported projects where nobody has clicked
   * "Schedule" yet.
   */
  private ScheduleResult ensureSchedule(UUID projectId) {
    return scheduleResultRepository.findTopByProjectIdAndStatusOrderByCalculatedAtDesc(projectId, ScheduleStatus.COMPLETED)
        .orElseGet(() -> {
          scheduleProject(projectId, SchedulingOption.RETAINED_LOGIC);
          return scheduleResultRepository.findTopByProjectIdAndStatusOrderByCalculatedAtDesc(projectId, ScheduleStatus.COMPLETED)
              .orElseThrow(() -> new ResourceNotFoundException("ScheduleResult", projectId));
        });
  }

  // -------------------------------------------------------------------------
  // Warning humanisation helpers (package-private for unit tests)
  // -------------------------------------------------------------------------

  private static final Pattern UUID_PATTERN = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /** Rewrites every activity UUID in each warning to its readable label. */
  static List<String> toReadableWarnings(List<String> warnings, Map<UUID, String> labelById) {
    if (warnings == null || warnings.isEmpty()) return warnings;
    return warnings.stream().map(w -> replaceUuids(w, labelById)).toList();
  }

  /** Replaces all UUID occurrences in {@code warning} that are present in {@code labelById}. */
  static String replaceUuids(String warning, Map<UUID, String> labelById) {
    return UUID_PATTERN.matcher(warning).replaceAll(m -> {
      String label = labelById.get(UUID.fromString(m.group()));
      return label != null ? Matcher.quoteReplacement(label) : m.group();
    });
  }

  /** Returns a human-readable label for an activity: "code - name", "code", "name", or UUID string. */
  private static String activityLabel(Activity a) {
    String code = a.getCode();
    String name = a.getName();
    boolean hasCode = code != null && !code.isBlank();
    boolean hasName = name != null && !name.isBlank();
    if (hasCode && hasName) return code + " - " + name;
    if (hasCode) return code;
    if (hasName) return name;
    return a.getId().toString();
  }
}
