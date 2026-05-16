package com.bipros.activity.application.service;

import com.bipros.activity.application.dto.ActivityResponse;
import com.bipros.activity.application.dto.CreateActivityRequest;
import com.bipros.activity.application.dto.SetSupervisorsRequest;
import com.bipros.activity.application.dto.SupervisorEntry;
import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.application.percent.ActivityStatusDerivation;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivityStepRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.common.dto.PagedResponse;
import com.bipros.common.event.ActivityCreatedEvent;
import com.bipros.common.event.ActivityUpdatedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.AccessSpecifications;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ActivityService {

  private final ActivityRepository activityRepository;
  private final ActivitySupervisorRepository activitySupervisorRepository;
  private final ActivityRelationshipRepository relationshipRepository;
  private final AuditService auditService;
  private final ProjectAccessGuard projectAccess;
  private final ProjectRepository projectRepository;
  private final PercentCompleteCalculator percentCompleteCalculator;
  private final ActivityStepRepository stepRepository;
  private final ApplicationEventPublisher eventPublisher;

  /** Cross-schema lookup of {@code resource.work_activities.default_unit} — keeps this module
   *  free of a Maven dep on {@code bipros-resource}, mirroring the precedent in
   *  {@code DailyActivityResourceOutputService}. Used only by list paths that bulk-resolve. */
  @PersistenceContext private EntityManager em;

  public ActivityResponse createActivity(CreateActivityRequest request) {
    log.info("Creating activity: code={}, name={}, projectId={}", request.code(), request.name(),
        request.projectId());

    projectAccess.requireEdit(request.projectId());

    if (request.plannedStartDate() != null
        && request.plannedFinishDate() != null
        && request.plannedFinishDate().isBefore(request.plannedStartDate())) {
      throw new BusinessRuleException(
          "INVALID_DATE_RANGE",
          "plannedFinishDate must be on or after plannedStartDate");
    }

    boolean isMilestone = request.activityType() != null
        && (request.activityType() == com.bipros.activity.domain.model.ActivityType.START_MILESTONE
            || request.activityType() == com.bipros.activity.domain.model.ActivityType.FINISH_MILESTONE);

    Activity activity = new Activity();
    activity.setCode(request.code());
    activity.setName(request.name());
    activity.setDescription(request.description());
    activity.setProjectId(request.projectId());
    activity.setWbsNodeId(request.wbsNodeId());

    if (request.activityType() != null) {
      activity.setActivityType(request.activityType());
    }
    if (request.durationType() != null) {
      activity.setDurationType(request.durationType());
    }
    if (request.percentCompleteType() != null) {
      activity.setPercentCompleteType(request.percentCompleteType());
    }

    // Milestones collapse to a point — plannedFinish := plannedStart. For START_MILESTONE, this
    // is the start date; for FINISH_MILESTONE, the finish date if supplied wins.
    LocalDate plannedStart = request.plannedStartDate();
    LocalDate plannedFinish = request.plannedFinishDate();
    if (isMilestone) {
      if (request.activityType() == com.bipros.activity.domain.model.ActivityType.FINISH_MILESTONE
          && plannedFinish != null) {
        plannedStart = plannedFinish;
      } else if (plannedStart != null) {
        plannedFinish = plannedStart;
      } else if (plannedFinish != null) {
        plannedStart = plannedFinish;
      }
    }
    activity.setPlannedStartDate(plannedStart);
    activity.setPlannedFinishDate(plannedFinish);
    UUID calendarId = resolveCalendarId(request.projectId(), request.calendarId());
    activity.setCalendarId(calendarId);
    activity.setChainageFromM(request.chainageFromM());
    activity.setChainageToM(request.chainageToM());
    activity.setWorkActivityId(request.workActivityId());
    activity.setCostAccountId(request.costAccountId());
    // Phase 4.5: responsibleResourceId / responsibleResourceName are gone from the DB
    // (Liquibase 094 dropped the columns). Supervisor identity is now carried by
    // supervisor_user_id and set via PUT /v1/activities/{id}/supervisor — the create path
    // no longer wires through a Resource-based supervisor. Intentional no-op.
    activity.setPercentComplete(0.0);

    Double duration;
    if (isMilestone) {
      // Milestones have zero duration; silently normalise any caller-supplied value.
      duration = 0.0;
    } else {
      duration = request.originalDuration();
      if (duration == null && plannedStart != null && plannedFinish != null) {
        duration = (double) java.time.temporal.ChronoUnit.DAYS.between(plannedStart, plannedFinish);
      }
    }
    activity.setOriginalDuration(duration);
    activity.setRemainingDuration(duration);

    Activity saved = activityRepository.save(activity);
    log.info("Activity created successfully: id={}", saved.getId());

    // Audit log creation
    auditService.logCreate("Activity", saved.getId(), ActivityResponse.from(saved));

    eventPublisher.publishEvent(
        new ActivityCreatedEvent(saved.getProjectId(), saved.getId(), saved.getCode(), saved.getName())
    );

    return ActivityResponse.from(saved);
  }

  public ActivityResponse updateActivity(UUID id, UpdateActivityRequest request) {
    log.info("Updating activity: id={}", id);

    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));

    // Activity-level ABAC: TEAM_MEMBER assignees may update their own activities even
    // without project-edit rights; everyone else must clear projectAccess.requireEdit.
    UUID currentUserId = projectAccess.currentUserId();
    boolean isAssignee = currentUserId != null
        && currentUserId.equals(activity.getAssignedTo());
    if (!isAssignee) {
      projectAccess.requireEdit(activity.getProjectId());
    }

    // Capture old values for audit BEFORE mutation
    String oldName = activity.getName();
    var oldStatus = activity.getStatus();
    Double oldOriginalDuration = activity.getOriginalDuration();
    Double oldRemainingDuration = activity.getRemainingDuration();
    Double oldPercentComplete = activity.getPercentComplete();
    var oldActualStart = activity.getActualStartDate();
    var oldActualFinish = activity.getActualFinishDate();

    if (request.name() != null) {
      activity.setName(request.name());
    }
    if (request.description() != null) {
      activity.setDescription(request.description());
    }

    if (request.wbsNodeId() != null) {
      activity.setWbsNodeId(request.wbsNodeId());
    }
    if (request.activityType() != null) {
      activity.setActivityType(request.activityType());
    }
    if (request.durationType() != null) {
      activity.setDurationType(request.durationType());
    }
    if (request.percentCompleteType() != null) {
      activity.setPercentCompleteType(request.percentCompleteType());
    }
    boolean statusExplicit = request.status() != null;
    if (statusExplicit) {
      activity.setStatus(request.status());
    }

    if (request.originalDuration() != null) {
      activity.setOriginalDuration(request.originalDuration());
    }
    if (request.remainingDuration() != null) {
      activity.setRemainingDuration(request.remainingDuration());
    }
    boolean progressChanged = request.percentComplete() != null
        || request.actualStartDate() != null
        || request.actualFinishDate() != null;
    if (request.percentComplete() != null) {
      // Determine effective percentCompleteType: if the request is also changing the type,
      // evaluate against the post-update type so the user can't sneak a manual write past it.
      PercentCompleteType effectiveType = request.percentCompleteType() != null
          ? request.percentCompleteType()
          : activity.getPercentCompleteType();
      if (effectiveType == null) {
        effectiveType = PercentCompleteType.DURATION;
      }
      if (effectiveType != PercentCompleteType.PHYSICAL) {
        throw new BusinessRuleException(
            "PERCENT_COMPLETE_NOT_MANUAL",
            "percentComplete is derived for type=" + effectiveType
                + "; for UNITS edit Daily Outputs, for DURATION edit actual dates / data date.");
      }
      // For PHYSICAL with steps, manual entry is also rejected
      if (effectiveType == PercentCompleteType.PHYSICAL
          && stepRepository.countByActivityId(id) > 0) {
        throw new BusinessRuleException(
            "PERCENT_COMPLETE_OWNED_BY_STEPS",
            "percentComplete is derived from activity steps for this activity. Edit step completion instead.");
      }
      activity.setPercentComplete(request.percentComplete());
    }
    if (request.physicalPercentComplete() != null) {
      activity.setPhysicalPercentComplete(request.physicalPercentComplete());
    }
    if (request.actualStartDate() != null) {
      activity.setActualStartDate(request.actualStartDate());
    }
    if (request.actualFinishDate() != null) {
      activity.setActualFinishDate(request.actualFinishDate());
    }

    // When progress changed but status wasn't explicitly set, derive status from the
    // same progress/actual-date signals that /progress uses.
    if (!statusExplicit && progressChanged) {
      applyStatusFromProgress(activity);
    }
    if (request.calendarId() != null) {
      activity.setCalendarId(request.calendarId());
    }
    if (request.primaryConstraintType() != null) {
      activity.setPrimaryConstraintType(request.primaryConstraintType());
    }
    if (request.primaryConstraintDate() != null) {
      activity.setPrimaryConstraintDate(request.primaryConstraintDate());
    }
    if (request.secondaryConstraintType() != null) {
      activity.setSecondaryConstraintType(request.secondaryConstraintType());
    }
    if (request.secondaryConstraintDate() != null) {
      activity.setSecondaryConstraintDate(request.secondaryConstraintDate());
    }
    if (request.suspendDate() != null) {
      activity.setSuspendDate(request.suspendDate());
    }
    if (request.resumeDate() != null) {
      activity.setResumeDate(request.resumeDate());
    }
    if (request.notes() != null) {
      activity.setNotes(request.notes());
    }
    if (request.chainageFromM() != null) {
      activity.setChainageFromM(request.chainageFromM());
    }
    if (request.chainageToM() != null) {
      activity.setChainageToM(request.chainageToM());
    }
    if (request.workActivityId() != null) {
      activity.setWorkActivityId(request.workActivityId());
    }
    // costAccountId: explicit null clears the value; non-null sets it
    if (request.costAccountId() != null) {
      activity.setCostAccountId(request.costAccountId());
    }
    // Phase 4.5: supervisor Resource fields are deprecated (the DB columns are dropped by
    // Liquibase 094). The request still carries them for back-compat with older frontends
    // but the assignment is now made via PUT /v1/activities/{id}/supervisor (supervisor_user_id).
    // Intentional no-op here.

    // Enforce date-order across the planned window after any updates
    LocalDate ps = activity.getPlannedStartDate();
    LocalDate pf = activity.getPlannedFinishDate();
    if (ps != null && pf != null && pf.isBefore(ps)) {
      throw new BusinessRuleException(
          "INVALID_DATE_RANGE",
          "plannedFinishDate must be on or after plannedStartDate");
    }

    if (progressChanged || statusExplicit) {
      validatePredecessorConstraints(activity);
    }

    Activity updated = activityRepository.save(activity);
    log.info("Activity updated successfully: id={}", id);

    // Audit log updates for key fields
    if (request.name() != null && !request.name().equals(oldName)) {
      auditService.logUpdate("Activity", id, "name", oldName, request.name());
    }
    if (request.status() != null && !request.status().equals(oldStatus)) {
      auditService.logUpdate("Activity", id, "status", oldStatus, request.status());
    }
    if (request.originalDuration() != null && !request.originalDuration().equals(oldOriginalDuration)) {
      auditService.logUpdate("Activity", id, "originalDuration", oldOriginalDuration, request.originalDuration());
    }
    if (request.remainingDuration() != null && !request.remainingDuration().equals(oldRemainingDuration)) {
      auditService.logUpdate("Activity", id, "remainingDuration", oldRemainingDuration, request.remainingDuration());
    }
    if (request.percentComplete() != null && !request.percentComplete().equals(oldPercentComplete)) {
      auditService.logUpdate("Activity", id, "percentComplete", oldPercentComplete, request.percentComplete());
    }
    if (request.actualStartDate() != null && !request.actualStartDate().equals(oldActualStart)) {
      auditService.logUpdate("Activity", id, "actualStartDate", oldActualStart, request.actualStartDate());
    }
    if (request.actualFinishDate() != null && !request.actualFinishDate().equals(oldActualFinish)) {
      auditService.logUpdate("Activity", id, "actualFinishDate", oldActualFinish, request.actualFinishDate());
    }

    eventPublisher.publishEvent(
        new ActivityUpdatedEvent(updated.getProjectId(), updated.getId(), updated.getCode(), updated.getName())
    );

    return ActivityResponse.from(updated);
  }

  public void deleteActivity(UUID id) {
    log.info("Deleting activity: id={}", id);

    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));

    projectAccess.requireEdit(activity.getProjectId());

    boolean hasRelationships = !relationshipRepository.findByPredecessorActivityId(id).isEmpty()
        || !relationshipRepository.findBySuccessorActivityId(id).isEmpty();

    if (hasRelationships) {
      throw new BusinessRuleException("ACTIVITY_HAS_RELATIONSHIPS",
          "Cannot delete activity with relationships. Remove relationships first.");
    }

    // Clear any supervisor rows first — Activity has no JPA cascade to ActivitySupervisor
    // (kept as a sibling aggregate to avoid pulling the supervisor list on every Activity load).
    activitySupervisorRepository.deleteByActivityId(id);
    activityRepository.deleteById(id);
    log.info("Activity deleted successfully: id={}", id);

    // Audit log deletion
    auditService.logDelete("Activity", id);
  }

  public ActivityResponse getActivity(UUID id) {
    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));
    projectAccess.requireRead(activity.getProjectId());
    // Resolve status date: project.dataDate if set, else today
    java.time.LocalDate statusDate = projectRepository.findById(activity.getProjectId())
        .map(Project::getDataDate)
        .orElse(java.time.LocalDate.now());
    List<SupervisorEntry> supervisors = loadSupervisorsByActivityId(List.of(id))
        .getOrDefault(id, List.of());
    return ActivityResponse.from(activity, percentCompleteCalculator, statusDate, supervisors);
  }

  public PagedResponse<ActivityResponse> listActivities(UUID projectId, Pageable pageable) {
    log.info("Listing activities for project: projectId={}, page={}, size={}", projectId,
        pageable.getPageNumber(), pageable.getPageSize());

    projectAccess.requireRead(projectId);

    Page<Activity> page = activityRepository.findByProjectIdOrderBySortOrder(projectId, pageable);
    Map<UUID, String> defaultUnitsByWorkActivity =
        bulkResolveWorkActivityDefaultUnits(page.getContent());
    Map<UUID, List<SupervisorEntry>> supervisorsByActivityId =
        loadSupervisorsByActivityId(page.getContent().stream().map(Activity::getId).toList());
    return PagedResponse.of(
        page.getContent().stream()
            .map(a -> ActivityResponse.from(a,
                a.getWorkActivityId() == null ? null : defaultUnitsByWorkActivity.get(a.getWorkActivityId()),
                supervisorsByActivityId.getOrDefault(a.getId(), List.of())))
            .toList(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.getNumber(),
        page.getSize()
    );
  }

  /**
   * Bulk fetch supervisor entries for the given activity ids. Preserves insertion order
   * (createdAt ASC) within each activity so the legacy first-supervisor cache stays stable.
   * Returns an empty map (not null) when the input is empty.
   */
  private Map<UUID, List<SupervisorEntry>> loadSupervisorsByActivityId(List<UUID> activityIds) {
    if (activityIds == null || activityIds.isEmpty()) return Map.of();
    // Copy into a mutable list — Spring Data may return an immutable view, and tests mock
    // with List.of(). Sort by createdAt so "first item" semantics are stable across reads.
    List<ActivitySupervisor> rows = new ArrayList<>(
        activitySupervisorRepository.findByActivityIdIn(activityIds));
    rows.sort((a, b) -> {
      if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
      if (a.getCreatedAt() == null) return -1;
      if (b.getCreatedAt() == null) return 1;
      return a.getCreatedAt().compareTo(b.getCreatedAt());
    });
    Map<UUID, List<SupervisorEntry>> out = new HashMap<>();
    for (ActivitySupervisor s : rows) {
      out.computeIfAbsent(s.getActivityId(), k -> new ArrayList<>())
          .add(new SupervisorEntry(s.getUserId(), s.getUserNameSnapshot()));
    }
    return out;
  }

  /**
   * One-shot lookup of {@code work_activities.default_unit} for every distinct work_activity_id
   * referenced by the page. Avoids N+1 queries when the page has many activities.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, String> bulkResolveWorkActivityDefaultUnits(List<Activity> activities) {
    if (em == null) return Map.of();
    Set<UUID> ids = activities.stream()
        .map(Activity::getWorkActivityId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    if (ids.isEmpty()) return Map.of();
    List<Object[]> rows = em.createNativeQuery(
            "SELECT id, default_unit FROM resource.work_activities WHERE id IN (:ids)")
        .setParameter("ids", ids)
        .getResultList();
    Map<UUID, String> out = new HashMap<>(rows.size());
    for (Object[] r : rows) {
      UUID id = (UUID) r[0];
      String unit = r[1] == null ? null : r[1].toString();
      out.put(id, unit);
    }
    return out;
  }

  public java.util.List<ActivityResponse> getActivitiesByWbs(UUID wbsNodeId) {
    log.info("Getting activities for WBS node: wbsNodeId={}", wbsNodeId);
    // Filter to activities the user may read (via the activity's projectId).
    java.util.Set<UUID> allowed = projectAccess.getAccessibleProjectIdsForCurrentUser();
    return activityRepository.findByWbsNodeId(wbsNodeId).stream()
        .filter(a -> allowed == null || allowed.contains(a.getProjectId()))
        .map(ActivityResponse::from)
        .toList();
  }

  public ActivityResponse updateProgress(UUID id, Double percentComplete, LocalDate actualStart,
      LocalDate actualFinish) {
    log.info("Updating progress for activity: id={}, percentComplete={}", id, percentComplete);

    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));

    if (percentComplete < 0 || percentComplete > 100) {
      throw new BusinessRuleException("INVALID_PERCENT_COMPLETE",
          "Percent complete must be between 0 and 100");
    }

    // Guard: reject manual % edits for non-PHYSICAL types
    PercentCompleteType pctType = activity.getPercentCompleteType();
    if (pctType == null) {
      pctType = PercentCompleteType.DURATION;
    }
    if (pctType != PercentCompleteType.PHYSICAL) {
      throw new BusinessRuleException(
          "PERCENT_COMPLETE_NOT_MANUAL",
          "percentComplete is derived for type=" + pctType
              + "; for UNITS edit Daily Outputs, for DURATION edit actual dates / data date.");
    }
    if (stepRepository.countByActivityId(id) > 0) {
      throw new BusinessRuleException(
          "PERCENT_COMPLETE_OWNED_BY_STEPS",
          "percentComplete is derived from activity steps for this activity. Edit step completion instead.");
    }

    Double oldPercent = activity.getPercentComplete();
    var oldActualStart = activity.getActualStartDate();
    var oldActualFinish = activity.getActualFinishDate();
    var oldStatus = activity.getStatus();

    activity.setPercentComplete(percentComplete);
    activity.setActualStartDate(actualStart);
    activity.setActualFinishDate(actualFinish);
    applyStatusFromProgress(activity);
    validatePredecessorConstraints(activity);

    Activity updated = activityRepository.save(activity);
    log.info("Progress updated successfully: id={}", id);

    if (!java.util.Objects.equals(oldStatus, updated.getStatus())) {
      auditService.logUpdate("Activity", id, "status", oldStatus, updated.getStatus());
    }

    // Audit progress changes
    if (!java.util.Objects.equals(percentComplete, oldPercent)) {
      auditService.logUpdate("Activity", id, "percentComplete", oldPercent, percentComplete);
    }
    if (!java.util.Objects.equals(actualStart, oldActualStart)) {
      auditService.logUpdate("Activity", id, "actualStartDate", oldActualStart, actualStart);
    }
    if (!java.util.Objects.equals(actualFinish, oldActualFinish)) {
      auditService.logUpdate("Activity", id, "actualFinishDate", oldActualFinish, actualFinish);
    }

    return ActivityResponse.from(updated);
  }

  /**
   * @deprecated Phase 4.5: this endpoint synced the legacy
   * {@code Activity.responsibleResourceId} cache, which is dropped by Liquibase 094. The
   * canonical supervisor wiring is now the per-activity
   * {@code PUT /v1/activities/{id}/supervisor} endpoint that writes
   * {@code Activity.supervisorUserId}. The method is preserved (no signature change) and
   * short-circuits to {@code 0} so older frontends do not 500. New callers must use the
   * user-based supervisor endpoint.
   */
  /**
   * Legacy single-supervisor write — kept for back-compat with older frontends and any
   * external integrations. Delegates to {@link #setSupervisors} with a one-element list,
   * which means it now REPLACES the entire supervisor set on the activity. Pass
   * {@code supervisorUserId = null} to clear.
   */
  public ActivityResponse setSupervisor(UUID activityId,
      com.bipros.activity.application.dto.SetSupervisorRequest request) {
    UUID userId = request == null ? null : request.supervisorUserId();
    String snapshot = request == null ? null : request.supervisorName();
    List<SupervisorEntry> list = userId == null
        ? List.of()
        : List.of(new SupervisorEntry(userId, snapshot));
    return setSupervisors(activityId, new SetSupervisorsRequest(list));
  }

  /**
   * Replace the supervisor set on an activity. All entries are equal — no primary.
   * Duplicate user ids in the request are deduplicated (first wins for the name
   * snapshot). The legacy single-column cache on {@code activities} is kept in sync
   * with the first entry for back-compat with consumers still reading
   * {@code supervisor_user_id} / {@code supervisor_user_name} directly.
   */
  public ActivityResponse setSupervisors(UUID activityId, SetSupervisorsRequest request) {
    Activity activity = activityRepository.findById(activityId)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));
    projectAccess.requireEdit(activity.getProjectId());

    List<SupervisorEntry> requested = request == null || request.supervisors() == null
        ? List.of()
        : request.supervisors();
    // Dedup preserving first occurrence; drop null userIds defensively.
    LinkedHashMap<UUID, String> deduped = new LinkedHashMap<>();
    for (SupervisorEntry e : requested) {
      if (e == null || e.userId() == null) continue;
      deduped.putIfAbsent(e.userId(), e.userName());
    }

    // Diff existing vs target and apply minimal mutations so createdAt timestamps survive
    // and the first-entry cache only flips when the first item actually changes.
    List<ActivitySupervisor> existing = activitySupervisorRepository.findByActivityId(activityId);
    Map<UUID, ActivitySupervisor> existingByUser = new HashMap<>();
    for (ActivitySupervisor s : existing) existingByUser.put(s.getUserId(), s);

    for (Map.Entry<UUID, String> entry : deduped.entrySet()) {
      ActivitySupervisor row = existingByUser.remove(entry.getKey());
      if (row == null) {
        ActivitySupervisor fresh = new ActivitySupervisor();
        fresh.setActivityId(activityId);
        fresh.setUserId(entry.getKey());
        fresh.setUserNameSnapshot(entry.getValue());
        activitySupervisorRepository.save(fresh);
      } else if (!java.util.Objects.equals(row.getUserNameSnapshot(), entry.getValue())) {
        row.setUserNameSnapshot(entry.getValue());
        activitySupervisorRepository.save(row);
      }
    }
    // Anything left in existingByUser was not in the request → remove.
    for (ActivitySupervisor stale : existingByUser.values()) {
      activitySupervisorRepository.delete(stale);
    }

    // Sync the legacy single-column cache. First entry wins; null when empty.
    if (deduped.isEmpty()) {
      activity.setSupervisorUserId(null);
      activity.setSupervisorUserName(null);
    } else {
      Map.Entry<UUID, String> first = deduped.entrySet().iterator().next();
      activity.setSupervisorUserId(first.getKey());
      activity.setSupervisorUserName(first.getValue());
    }
    Activity saved = activityRepository.save(activity);
    log.info("Set supervisors: activityId={}, count={}", activityId, deduped.size());

    List<SupervisorEntry> attached = loadSupervisorsByActivityId(List.of(activityId))
        .getOrDefault(activityId, List.of());
    return ActivityResponse.from(saved, (String) null, attached);
  }

  @Deprecated(forRemoval = true)
  public int bulkSetSupervisor(UUID projectId, com.bipros.activity.application.dto.BulkSupervisorRequest request) {
    log.warn("Phase 4.5: bulkSetSupervisor is a no-op (responsibleResourceId column dropped). "
        + "Use the supervisor_user_id endpoint instead. projectId={}, requested={}",
        projectId, request != null && request.activityIds() != null ? request.activityIds().size() : 0);
    projectAccess.requireEdit(projectId);
    return 0;
  }

  public void applyActuals(UUID projectId, LocalDate dataDate) {
    log.info("Applying actuals for project: projectId={}, dataDate={}", projectId, dataDate);

    java.util.List<Activity> activities = activityRepository.findByProjectId(projectId);

    for (Activity activity : activities) {
      // Skip LOCKED activities — their plan and actuals are frozen. DPR-driven
      // cascade writes are the only mutator that bypasses this; the batch apply-actuals
      // is treated like a manual cascade and respects the lock.
      if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        continue;
      }
      boolean updated = false;

      // Auto-stamp actuals from the data date — this is what `applyActuals` adds
      // on top of the calculator. The calculator itself does not write actual dates.
      if (activity.getPlannedStartDate() != null &&
          activity.getPlannedStartDate().compareTo(dataDate) <= 0 &&
          activity.getActualStartDate() == null) {
        activity.setActualStartDate(activity.getPlannedStartDate());
        updated = true;
      }

      if (activity.getPlannedFinishDate() != null &&
          activity.getPlannedFinishDate().compareTo(dataDate) <= 0 &&
          activity.getActualFinishDate() == null) {
        activity.setActualFinishDate(activity.getPlannedFinishDate());
        updated = true;
      }

      // Delegate % / status / forced-finish to the single source of truth so
      // /apply-actuals matches the nightly DurationPercentCompleteJob and the
      // on-read recompute. UNITS rollups are event-driven elsewhere — pass null
      // sums and let calculateUnits return KEEP_PRIOR for those.
      var result = percentCompleteCalculator.calculate(activity, null, null, dataDate);
      if (!result.isKeepPrior()) {
        if (result.percent() != null
            && !java.util.Objects.equals(result.percent(), activity.getPercentComplete())) {
          activity.setPercentComplete(result.percent());
          updated = true;
        }
        if (result.status() != null && result.status() != activity.getStatus()) {
          activity.setStatus(result.status());
          updated = true;
        }
        if (result.forcedActualFinish() != null && activity.getActualFinishDate() == null) {
          activity.setActualFinishDate(result.forcedActualFinish());
          updated = true;
        }
      }

      if (updated) {
        activityRepository.save(activity);
        auditService.logUpdate("Activity", activity.getId(), "applyActuals",
            null, "Auto-applied actuals for dataDate=" + dataDate);
      }
    }

    log.info("Actuals applied successfully for project: projectId={}", projectId);
  }

  /**
   * Flip the activity to LOCKED. Idempotent — locking an already-locked activity
   * is a no-op success. Requires {@code ACTIVITY.LOCK} on the project (enforced
   * at the controller boundary).
   */
  public ActivityResponse lockActivity(UUID id) {
    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));
    if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
      return ActivityResponse.from(activity);
    }
    ActivityEditStatus prior = activity.getEditStatus();
    activity.setEditStatus(ActivityEditStatus.LOCKED);
    Activity saved = activityRepository.save(activity);
    log.info("Activity locked: id={}", id);
    auditService.logUpdate("Activity", id, "editStatus", prior, ActivityEditStatus.LOCKED);
    return ActivityResponse.from(saved);
  }

  /**
   * Flip the activity back to DRAFT. Idempotent. Requires {@code ACTIVITY.UNLOCK}
   * on the project (enforced at the controller boundary). DPRs already submitted
   * while locked are unaffected; they continue to exist and their cascade history
   * is preserved.
   */
  public ActivityResponse unlockActivity(UUID id) {
    Activity activity = activityRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", id));
    if (activity.getEditStatus() == ActivityEditStatus.DRAFT) {
      return ActivityResponse.from(activity);
    }
    ActivityEditStatus prior = activity.getEditStatus();
    activity.setEditStatus(ActivityEditStatus.DRAFT);
    Activity saved = activityRepository.save(activity);
    log.info("Activity unlocked: id={}", id);
    auditService.logUpdate("Activity", id, "editStatus", prior, ActivityEditStatus.DRAFT);
    return ActivityResponse.from(saved);
  }

  /**
   * Derive status from progress. Single source of truth used by both
   * {@link #updateProgress} and {@link #updateActivity} (when the caller
   * hasn't passed an explicit status).
   * <ul>
   *   <li>percentComplete ≥ 100 → COMPLETED</li>
   *   <li>percentComplete &gt; 0 OR actualStartDate set → IN_PROGRESS</li>
   *   <li>otherwise → NOT_STARTED</li>
   * </ul>
   */
  private void applyStatusFromProgress(Activity activity) {
    ActivityStatus derived = ActivityStatusDerivation.derive(activity);
    activity.setStatus(derived);
  }

  /**
   * Block out-of-sequence actuals. Each dependency type gates a different transition:
   * <ul>
   *   <li>FS — successor cannot start until predecessor finishes</li>
   *   <li>SS — successor cannot start until predecessor starts</li>
   *   <li>FF — successor cannot finish until predecessor finishes</li>
   *   <li>SF — successor cannot finish until predecessor starts</li>
   * </ul>
   * Lag values aren't enforced here — only the gating-date existence check, since
   * planners often need to log actuals that occurred earlier than the lag would allow.
   * Cross-project (external) relationships are skipped because the predecessor
   * activity isn't queryable from this service.
   */
  private void validatePredecessorConstraints(Activity activity) {
    Double pct = activity.getPercentComplete();
    boolean claimsStarted = activity.getActualStartDate() != null
        || (pct != null && pct > 0.0)
        || activity.getStatus() == ActivityStatus.IN_PROGRESS
        || activity.getStatus() == ActivityStatus.COMPLETED;
    boolean claimsFinished = activity.getActualFinishDate() != null
        || (pct != null && pct >= 100.0)
        || activity.getStatus() == ActivityStatus.COMPLETED;
    if (!claimsStarted && !claimsFinished) {
      return;
    }

    List<ActivityRelationship> predecessors =
        relationshipRepository.findBySuccessorActivityId(activity.getId());
    for (ActivityRelationship rel : predecessors) {
      if (Boolean.TRUE.equals(rel.getIsExternal())) {
        continue;
      }
      Activity pred = activityRepository.findById(rel.getPredecessorActivityId()).orElse(null);
      if (pred == null) {
        continue;
      }
      switch (rel.getRelationshipType()) {
        case FINISH_TO_START -> {
          if (claimsStarted && pred.getActualFinishDate() == null) {
            throw predecessorViolation(activity, pred, "start", "finished", "FS", rel.getLag());
          }
        }
        case START_TO_START -> {
          if (claimsStarted && pred.getActualStartDate() == null) {
            throw predecessorViolation(activity, pred, "start", "started", "SS", rel.getLag());
          }
        }
        case FINISH_TO_FINISH -> {
          if (claimsFinished && pred.getActualFinishDate() == null) {
            throw predecessorViolation(activity, pred, "finish", "finished", "FF", rel.getLag());
          }
        }
        case START_TO_FINISH -> {
          if (claimsFinished && pred.getActualStartDate() == null) {
            throw predecessorViolation(activity, pred, "finish", "started", "SF", rel.getLag());
          }
        }
      }
    }
  }

  private static BusinessRuleException predecessorViolation(
      Activity activity, Activity pred, String successorVerb, String predecessorState,
      String typeCode, Double lag) {
    String lagSuffix = formatLag(lag);
    String message = String.format(
        "Cannot %s %s — predecessor %s (%s) has not %s. Dependency: %s%s.",
        successorVerb, activity.getCode(), pred.getCode(), pred.getName(),
        predecessorState, typeCode, lagSuffix);
    return new BusinessRuleException("PREDECESSOR_NOT_SATISFIED", message);
  }

  private static String formatLag(Double lag) {
    if (lag == null || lag == 0.0) {
      return "";
    }
    long days = Math.round(Math.abs(lag));
    return lag > 0 ? " + " + days + "d" : " - " + days + "d";
  }

  /**
   * Returns the explicit {@code calendarId} if supplied; otherwise falls back to the
   * project's default calendar (P6-style project-calendar inheritance).
   */
  private UUID resolveCalendarId(UUID projectId, UUID explicitCalendarId) {
    if (explicitCalendarId != null) {
      return explicitCalendarId;
    }
    Project project = projectRepository.findById(projectId).orElse(null);
    return project != null ? project.getCalendarId() : null;
  }

  /**
   * List activities under {@code projectId} that have no {@code work_activity_id} linked AND
   * either have at least one DPR submitted in the [from, to] window or have planned dates that
   * intersect the window. Powers the "N activities have no Work Activity linked" banner on the
   * Capacity Utilization page.
   *
   * <p>The query crosses the project schema (for the DPR existence check) so a native SQL is
   * used; the same precedent applies as elsewhere in this service.
   */
  @Transactional(readOnly = true)
  public List<com.bipros.activity.application.dto.MissingWorkActivityRow> listMissingWorkActivity(
      UUID projectId, LocalDate from, LocalDate to) {
    projectAccess.requireRead(projectId);
    LocalDate fromDate = from != null ? from : LocalDate.now().minusYears(5);
    LocalDate toDate = to != null ? to : LocalDate.now().plusYears(5);

    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT a.id, a.code, a.name, "
                + "  (SELECT COUNT(*) FROM project.daily_progress_reports d "
                + "     WHERE d.activity_id = a.id AND d.report_date BETWEEN :fromDate AND :toDate) "
                + "FROM activity.activities a "
                + "WHERE a.project_id = :projectId "
                + "  AND a.work_activity_id IS NULL "
                + "  AND ( "
                + "    EXISTS (SELECT 1 FROM project.daily_progress_reports d "
                + "             WHERE d.activity_id = a.id AND d.report_date BETWEEN :fromDate AND :toDate) "
                + "    OR (a.planned_start_date <= :toDate AND a.planned_finish_date >= :fromDate) "
                + "  ) "
                + "ORDER BY a.code")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .getResultList();

    return rows.stream()
        .map(r -> new com.bipros.activity.application.dto.MissingWorkActivityRow(
            (UUID) r[0],
            (String) r[1],
            (String) r[2],
            r[3] == null ? 0 : ((Number) r[3]).intValue()))
        .toList();
  }
}
