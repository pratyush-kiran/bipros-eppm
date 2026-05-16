package com.bipros.baseline.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.baseline.application.dto.BaselineActivityResponse;
import com.bipros.baseline.application.dto.BaselineDetailResponse;
import com.bipros.baseline.application.dto.BaselineResponse;
import com.bipros.baseline.application.dto.BaselineVarianceResponse;
import com.bipros.baseline.application.dto.CreateBaselineRequest;
import com.bipros.baseline.application.dto.ScheduleComparisonResponse;
import com.bipros.baseline.application.dto.UpdateBaselineRequest;
import com.bipros.baseline.domain.Baseline;
import com.bipros.baseline.domain.BaselineActivity;
import com.bipros.baseline.domain.BaselineExpense;
import com.bipros.baseline.domain.BaselineRelationship;
import com.bipros.baseline.domain.BaselineResourceAssignment;
import com.bipros.baseline.domain.BaselineWbs;
import com.bipros.baseline.infrastructure.repository.BaselineActivityRepository;
import com.bipros.baseline.infrastructure.repository.BaselineExpenseRepository;
import com.bipros.baseline.infrastructure.repository.BaselineRelationshipRepository;
import com.bipros.baseline.infrastructure.repository.BaselineRepository;
import com.bipros.baseline.infrastructure.repository.BaselineResourceAssignmentRepository;
import com.bipros.baseline.infrastructure.repository.BaselineWbsRepository;
import com.bipros.common.event.BaselineCapturedEvent;
import com.bipros.common.event.BaselineDeactivatedEvent;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.cost.application.service.ActivityCostCalculator;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BaselineService {

  private final BaselineRepository baselineRepository;
  private final BaselineActivityRepository baselineActivityRepository;
  private final BaselineRelationshipRepository baselineRelationshipRepository;
  // Phase 5: snapshot completeness — WBS, per-resource, per-expense.
  private final BaselineWbsRepository baselineWbsRepository;
  private final BaselineResourceAssignmentRepository baselineResourceAssignmentRepository;
  private final BaselineExpenseRepository baselineExpenseRepository;
  private final ActivityRepository activityRepository;
  private final ActivityRelationshipRepository activityRelationshipRepository;
  private final ActivityExpenseRepository activityExpenseRepository;
  private final ResourceAssignmentRepository resourceAssignmentRepository;
  private final ProjectRepository projectRepository;
  private final WbsNodeRepository wbsNodeRepository;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public BaselineResponse createBaseline(UUID projectId, CreateBaselineRequest request) {
    // Enforce name-uniqueness per project+type so the schedule history can't collect
    // indistinguishable "BL-QA-001" snapshots (BUG-037).
    List<Baseline> existingBaselines =
        baselineRepository.findByProjectIdAndBaselineType(projectId, request.baselineType());
    for (Baseline baseline : existingBaselines) {
      if (request.name() != null && request.name().equalsIgnoreCase(baseline.getName())) {
        throw new com.bipros.common.exception.BusinessRuleException(
            "DUPLICATE_CODE",
            "A baseline named '" + request.name() + "' already exists for this project and type");
      }
      boolean wasActive = Boolean.TRUE.equals(baseline.getIsActive());
      baseline.setIsActive(false);
      baselineRepository.save(baseline);
      if (wasActive) {
        eventPublisher.publishEvent(
            new BaselineDeactivatedEvent(projectId, baseline.getId())
        );
      }
    }

    // Phase 4.3: when sourceProjectId is supplied, snapshot from THAT project's data instead of
    // the target's. The resulting baseline is still attached to the target — variance/comparison
    // remain a function of (target activities) vs (snapshotted activities), so a meaningful
    // comparison only happens when activity IDs overlap (typical for "saved as copy" workflows).
    UUID snapshotSourceId = request.sourceProjectId() != null ? request.sourceProjectId() : projectId;

    // Load all activities for the snapshot source
    List<Activity> activities = activityRepository.findByProjectId(snapshotSourceId);

    // Load cost data
    List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(snapshotSourceId);
    Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
        .filter(e -> e.getActivityId() != null)
        .collect(Collectors.groupingBy(ActivityExpense::getActivityId));

    List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(snapshotSourceId);
    Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
        .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));

    // Compute project-level metrics BEFORE the first save so totalActivities/totalCost/
    // project dates land in the initial INSERT — otherwise the response DTO may be serialised
    // from a stale entity reference and render as 0/null (BUG-038).
    BigDecimal totalCost = BigDecimal.ZERO;
    LocalDate projectStart = null;
    LocalDate projectFinish = null;
    java.util.List<BaselineActivity> stagedActivities = new java.util.ArrayList<>(activities.size());

    for (Activity activity : activities) {
      BigDecimal plannedCost = ActivityCostCalculator.calculatePlannedCost(
          activity.getId(), expensesByActivity, assignmentsByActivity);
      BigDecimal actualCost = ActivityCostCalculator.calculateActualCost(
          activity.getId(), expensesByActivity, assignmentsByActivity);

      BaselineActivity ba = new BaselineActivity();
      ba.setActivityId(activity.getId());
      ba.setEarlyStart(activity.getPlannedStartDate());
      ba.setEarlyFinish(activity.getPlannedFinishDate());
      ba.setLateStart(activity.getLateStartDate());
      ba.setLateFinish(activity.getLateFinishDate());
      ba.setOriginalDuration(activity.getOriginalDuration());
      ba.setRemainingDuration(activity.getRemainingDuration());
      ba.setTotalFloat(activity.getTotalFloat());
      ba.setFreeFloat(activity.getFreeFloat());
      ba.setPlannedCost(plannedCost);
      ba.setActualCost(actualCost);
      ba.setPercentComplete(activity.getPercentComplete());
      stagedActivities.add(ba);

      totalCost = totalCost.add(plannedCost);

      if (activity.getPlannedStartDate() != null) {
        if (projectStart == null || activity.getPlannedStartDate().isBefore(projectStart)) {
          projectStart = activity.getPlannedStartDate();
        }
      }
      if (activity.getPlannedFinishDate() != null) {
        if (projectFinish == null || activity.getPlannedFinishDate().isAfter(projectFinish)) {
          projectFinish = activity.getPlannedFinishDate();
        }
      }
    }

    Baseline baseline = new Baseline();
    baseline.setProjectId(projectId);
    baseline.setName(request.name());
    baseline.setDescription(request.description());
    baseline.setBaselineType(request.baselineType());
    baseline.setBaselineDate(LocalDate.now());
    baseline.setIsActive(true);
    baseline.setTotalActivities(activities.size());
    baseline.setTotalCost(totalCost);
    baseline.setProjectStartDate(projectStart);
    baseline.setProjectFinishDate(projectFinish);
    baseline.setProjectDuration(projectStart != null && projectFinish != null
        ? (double) ChronoUnit.DAYS.between(projectStart, projectFinish)
        : 0.0);
    Baseline saved = baselineRepository.save(baseline);

    for (BaselineActivity ba : stagedActivities) {
      ba.setBaselineId(saved.getId());
      baselineActivityRepository.save(ba);
    }

    // Snapshot relationships too. Without this the BaselineRelationship table stays empty and
    // Restore (Phase 4.1) has nothing to write back. Mirrors the activity-snapshot pass — uses
    // snapshotSourceId so "convert other project" copies the source's relationships.
    List<ActivityRelationship> sourceRelationships =
        activityRelationshipRepository.findByProjectId(snapshotSourceId);
    for (ActivityRelationship rel : sourceRelationships) {
      BaselineRelationship br = new BaselineRelationship();
      br.setBaselineId(saved.getId());
      br.setPredecessorActivityId(rel.getPredecessorActivityId());
      br.setSuccessorActivityId(rel.getSuccessorActivityId());
      br.setRelationshipType(rel.getRelationshipType() != null
          ? rel.getRelationshipType().name() : null);
      br.setLag(rel.getLag());
      baselineRelationshipRepository.save(br);
    }

    // Phase 5.1: WBS snapshot.
    List<WbsNode> wbsNodes = wbsNodeRepository.findByProjectIdOrderBySortOrder(snapshotSourceId);
    for (WbsNode node : wbsNodes) {
      BaselineWbs bw = BaselineWbs.builder()
          .baselineId(saved.getId())
          .wbsNodeId(node.getId())
          .code(node.getCode())
          .name(node.getName())
          .parentId(node.getParentId())
          .wbsLevel(node.getWbsLevel())
          .sortOrder(node.getSortOrder())
          .plannedStart(node.getPlannedStart())
          .plannedFinish(node.getPlannedFinish())
          .budgetCrores(node.getBudgetCrores())
          .build();
      baselineWbsRepository.save(bw);
    }

    // Phase 5.2: per-resource-assignment snapshot.
    for (ResourceAssignment ra : allAssignments) {
      BaselineResourceAssignment bra = BaselineResourceAssignment.builder()
          .baselineId(saved.getId())
          .assignmentId(ra.getId())
          .activityId(ra.getActivityId())
          .resourceId(ra.getResourceId())
          .roleId(ra.getRoleId())
          .budgetedUnits(ra.getBudgetedUnits())
          .budgetedCost(ra.getBudgetedCost())
          .plannedUnits(ra.getPlannedUnits())
          .plannedCost(ra.getPlannedCost())
          .rateType(ra.getRateType())
          .build();
      baselineResourceAssignmentRepository.save(bra);
    }

    // Phase 5.3: per-expense snapshot.
    for (ActivityExpense expense : allExpenses) {
      BaselineExpense be = BaselineExpense.builder()
          .baselineId(saved.getId())
          .expenseId(expense.getId())
          .activityId(expense.getActivityId())
          .name(expense.getName())
          .expenseCategory(expense.getExpenseCategory())
          .budgetedCost(expense.getBudgetedCost())
          .plannedStartDate(expense.getPlannedStartDate())
          .plannedFinishDate(expense.getPlannedFinishDate())
          .build();
      baselineExpenseRepository.save(be);
    }

    // Auto-activate the first baseline a project gets — variance reports are useless
    // until SOMETHING is the reference, and forcing the user to click "Activate" right
    // after "Create" is friction. Subsequent baselines do not auto-replace the PRIMARY
    // one; the user has to explicitly switch via assignBaselineToSlot().
    // Taking any new baseline also clears the requires_rebaseline nudge — the new
    // snapshot is, by definition, current.
    // Phase 3: write to the new primary_baseline_id slot. The legacy active_baseline_id
    // column is mirrored alongside so deprecated callers stay working during the
    // deprecation window.
    projectRepository.findById(projectId).ifPresent(p -> {
      boolean dirty = false;
      if (p.getPrimaryBaselineId() == null) {
        p.setPrimaryBaselineId(saved.getId());
        p.setActiveBaselineId(saved.getId());
        dirty = true;
      }
      if (p.isRequiresRebaseline()) {
        p.setRequiresRebaseline(false);
        dirty = true;
      }
      if (dirty) projectRepository.save(p);
    });

    auditService.logCreate("Baseline", saved.getId(), BaselineResponse.from(saved));

    eventPublisher.publishEvent(
        new BaselineCapturedEvent(saved.getProjectId(), saved.getId(), saved.getName())
    );

    return BaselineResponse.from(saved);
  }

  /** P6-style baseline-assignment slot. Phase 3 of the baseline-progress roadmap. */
  public enum BaselineSlot {
    PRIMARY,
    SECONDARY,
    TERTIARY
  }

  /**
   * Set the given baseline as the project's active (PRIMARY) reference. P6 calls this the
   * "Project Baseline". Idempotent — calling it with the already-active baseline is fine.
   *
   * <p><strong>Deprecated as of Phase 3.</strong> Now a thin wrapper over
   * {@link #assignBaselineToSlot(UUID, UUID, BaselineSlot)} with {@code PRIMARY}. Kept on the
   * public API for one release so AI tools / EVM / listeners that still call
   * {@code setActiveBaseline} keep working.
   */
  @Deprecated
  @Transactional
  public BaselineResponse setActiveBaseline(UUID projectId, UUID baselineId) {
    return assignBaselineToSlot(projectId, baselineId, BaselineSlot.PRIMARY);
  }

  /**
   * Phase 3: assign a baseline to one of the three P6 slots on a project. Slots are
   * independent — a single baseline can be assigned to multiple slots (P6 allows this; useful
   * when the same snapshot serves as both the live PRIMARY and a stable TERTIARY history
   * reference).
   *
   * <p>The PRIMARY slot is mirrored into the legacy {@code activeBaselineId} column so
   * deprecated callers keep returning the right baseline during the deprecation window.
   */
  @Transactional
  public BaselineResponse assignBaselineToSlot(UUID projectId, UUID baselineId, BaselineSlot slot) {
    Baseline baseline = baselineRepository.findById(baselineId)
        .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));
    if (!baseline.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Baseline", baselineId);
    }
    Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

    UUID previous = readSlot(project, slot);
    writeSlot(project, slot, baselineId);
    if (slot == BaselineSlot.PRIMARY) {
      // Keep the legacy column in lockstep so deprecated readers (AI tools, EVM, listeners)
      // see the same baseline as the new PRIMARY slot.
      project.setActiveBaselineId(baselineId);
    }
    projectRepository.save(project);
    auditService.logUpdate("Project", projectId, slotFieldName(slot), previous, baselineId);
    return BaselineResponse.from(baseline);
  }

  /** Phase 3: detach whichever baseline currently occupies the given slot. */
  @Transactional
  public void clearBaselineSlot(UUID projectId, BaselineSlot slot) {
    Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    UUID previous = readSlot(project, slot);
    if (previous == null) return;
    writeSlot(project, slot, null);
    if (slot == BaselineSlot.PRIMARY) {
      project.setActiveBaselineId(null);
    }
    projectRepository.save(project);
    auditService.logUpdate("Project", projectId, slotFieldName(slot), previous, null);
  }

  private UUID readSlot(Project project, BaselineSlot slot) {
    return switch (slot) {
      case PRIMARY -> project.getPrimaryBaselineId();
      case SECONDARY -> project.getSecondaryBaselineId();
      case TERTIARY -> project.getTertiaryBaselineId();
    };
  }

  private void writeSlot(Project project, BaselineSlot slot, UUID baselineId) {
    switch (slot) {
      case PRIMARY -> project.setPrimaryBaselineId(baselineId);
      case SECONDARY -> project.setSecondaryBaselineId(baselineId);
      case TERTIARY -> project.setTertiaryBaselineId(baselineId);
    }
  }

  private String slotFieldName(BaselineSlot slot) {
    return switch (slot) {
      case PRIMARY -> "primaryBaselineId";
      case SECONDARY -> "secondaryBaselineId";
      case TERTIARY -> "tertiaryBaselineId";
    };
  }

  public BaselineDetailResponse getBaseline(UUID baselineId) {
    Baseline baseline =
        baselineRepository
            .findById(baselineId)
            .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));

    List<BaselineActivity> activities = baselineActivityRepository.findByBaselineId(baselineId);
    List<BaselineActivityResponse> activityResponses =
        activities.stream().map(BaselineActivityResponse::from).toList();

    return new BaselineDetailResponse(
        BaselineResponse.from(baseline), activityResponses);
  }

  public List<BaselineResponse> listBaselines(UUID projectId) {
    return baselineRepository.findByProjectId(projectId).stream()
        .map(BaselineResponse::from)
        .toList();
  }

  /**
   * Phase 4.1: P6-style "Restore Baseline" — write the snapshot back onto the live project.
   * Overwrites planned dates, durations, and relationships from the snapshot. Actuals
   * ({@code actualStartDate}, {@code actualFinishDate}, {@code percentComplete}) are preserved
   * so the planner doesn't lose progress data.
   *
   * <p>Destructive (no undo). Caller must confirm in the UI before invoking. Audit-logged so
   * the action is queryable later.
   */
  @Transactional
  public BaselineResponse restoreBaseline(UUID projectId, UUID baselineId) {
    Baseline baseline = baselineRepository.findById(baselineId)
        .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));
    if (!baseline.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Baseline", baselineId);
    }

    List<BaselineActivity> snapshotActivities =
        baselineActivityRepository.findByBaselineId(baselineId);
    Map<UUID, BaselineActivity> snapshotByActivityId = snapshotActivities.stream()
        .collect(Collectors.toMap(BaselineActivity::getActivityId, ba -> ba));

    List<Activity> liveActivities = activityRepository.findByProjectId(projectId);
    int restored = 0;
    int skippedLocked = 0;
    for (Activity activity : liveActivities) {
      BaselineActivity snap = snapshotByActivityId.get(activity.getId());
      if (snap == null) continue; // activity didn't exist when the snapshot was taken — skip
      if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
        skippedLocked++;
        continue;
      }
      // Planned dates come from earlyStart/earlyFinish (createBaseline copies them from
      // plannedStart/plannedFinish, so they are the right round-trip target).
      activity.setPlannedStartDate(snap.getEarlyStart());
      activity.setPlannedFinishDate(snap.getEarlyFinish());
      activity.setEarlyStartDate(snap.getEarlyStart());
      activity.setEarlyFinishDate(snap.getEarlyFinish());
      activity.setLateStartDate(snap.getLateStart());
      activity.setLateFinishDate(snap.getLateFinish());
      activity.setOriginalDuration(snap.getOriginalDuration());
      // remainingDuration comes from the snapshot only when the activity has no progress;
      // otherwise we keep the live remainingDuration so actuals aren't clobbered.
      if (activity.getActualStartDate() == null) {
        activity.setRemainingDuration(snap.getRemainingDuration());
      }
      activity.setTotalFloat(snap.getTotalFloat());
      activity.setFreeFloat(snap.getFreeFloat());
      // Intentionally NOT touched: actualStartDate, actualFinishDate, percentComplete, status —
      // restore should preserve actuals so the planner doesn't lose progress data.
      activityRepository.save(activity);
      restored++;
    }
    if (skippedLocked > 0) {
      log.info("Skipped {} LOCKED activities", skippedLocked);
    }

    // Restore relationships: delete every existing relationship for the project, then re-create
    // from the snapshot. Same destructive semantics as P6 — restoring a baseline restores the
    // logic too. Activities that no longer exist are silently dropped from the snapshot.
    Map<UUID, Activity> liveById = liveActivities.stream()
        .collect(Collectors.toMap(Activity::getId, a -> a));
    activityRelationshipRepository.deleteAll(
        activityRelationshipRepository.findByProjectId(projectId));
    List<BaselineRelationship> snapshotRelationships =
        baselineRelationshipRepository.findByBaselineId(baselineId);
    for (BaselineRelationship br : snapshotRelationships) {
      if (!liveById.containsKey(br.getPredecessorActivityId())
          || !liveById.containsKey(br.getSuccessorActivityId())) {
        continue;
      }
      ActivityRelationship rel = new ActivityRelationship();
      rel.setProjectId(projectId);
      rel.setPredecessorActivityId(br.getPredecessorActivityId());
      rel.setSuccessorActivityId(br.getSuccessorActivityId());
      rel.setRelationshipType(parseRelationshipType(br.getRelationshipType()));
      rel.setLag(br.getLag() != null ? br.getLag() : 0.0);
      rel.setIsExternal(false);
      activityRelationshipRepository.save(rel);
    }

    auditService.logUpdate("Project", projectId, "restoreBaseline", null,
        "baselineId=" + baselineId + ", activitiesRestored=" + restored);
    return BaselineResponse.from(baseline);
  }

  /**
   * Phase 4.2: Selective Update Baseline. Re-snapshots a subset of an existing baseline's rows
   * from the live project, scoped by {@link UpdateBaselineRequest#activityIds()}, the
   * critical-only / milestones-only / status / date-range filters, and the field-category
   * toggles.
   *
   * <p>Behaviour:
   * <ul>
   *   <li>Activities matching the filter that are already in the snapshot are <em>updated</em>
   *       in-place — only the field categories whose toggles are true.</li>
   *   <li>Activities matching the filter that are not yet in the snapshot are <em>inserted</em>
   *       (closing the gap that opens when an activity is created after the baseline was taken).</li>
   *   <li>Relationships are fully replaced when {@code updateRelationships} is true,
   *       restricted to those touching the matching activities.</li>
   * </ul>
   */
  @Transactional
  public BaselineResponse updateBaseline(UUID projectId, UUID baselineId, UpdateBaselineRequest request) {
    Baseline baseline = baselineRepository.findById(baselineId)
        .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));
    if (!baseline.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Baseline", baselineId);
    }

    List<Activity> liveActivities = activityRepository.findByProjectId(projectId);
    List<Activity> filtered = liveActivities.stream()
        .filter(a -> matchesFilter(a, request))
        .toList();
    Set<UUID> filteredIds = filtered.stream().map(Activity::getId).collect(Collectors.toSet());

    Map<UUID, BaselineActivity> existingByActivity =
        baselineActivityRepository.findByBaselineId(baselineId).stream()
            .collect(Collectors.toMap(BaselineActivity::getActivityId, ba -> ba));

    Map<UUID, List<ActivityExpense>> expensesByActivity = activityExpenseRepository
        .findByProjectId(projectId).stream()
        .filter(e -> e.getActivityId() != null)
        .collect(Collectors.groupingBy(ActivityExpense::getActivityId));
    Map<UUID, List<ResourceAssignment>> assignmentsByActivity = resourceAssignmentRepository
        .findByProjectId(projectId).stream()
        .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));

    int updated = 0;
    int inserted = 0;
    for (Activity a : filtered) {
      BaselineActivity ba = existingByActivity.get(a.getId());
      boolean isNew = ba == null;
      if (isNew) {
        ba = new BaselineActivity();
        ba.setBaselineId(baselineId);
        ba.setActivityId(a.getId());
      }
      if (request.dates()) {
        ba.setEarlyStart(a.getPlannedStartDate());
        ba.setEarlyFinish(a.getPlannedFinishDate());
        ba.setLateStart(a.getLateStartDate());
        ba.setLateFinish(a.getLateFinishDate());
      }
      if (request.durations()) {
        ba.setOriginalDuration(a.getOriginalDuration());
        ba.setRemainingDuration(a.getRemainingDuration());
        ba.setTotalFloat(a.getTotalFloat());
        ba.setFreeFloat(a.getFreeFloat());
      }
      if (request.resourceCosts() || request.expenseCosts()) {
        BigDecimal planned = ActivityCostCalculator.calculatePlannedCost(
            a.getId(), expensesByActivity, assignmentsByActivity);
        BigDecimal actual = ActivityCostCalculator.calculateActualCost(
            a.getId(), expensesByActivity, assignmentsByActivity);
        ba.setPlannedCost(planned);
        ba.setActualCost(actual);
      }
      ba.setPercentComplete(a.getPercentComplete());
      baselineActivityRepository.save(ba);
      if (isNew) inserted++; else updated++;
    }

    if (request.relationships()) {
      // Replace only the relationships whose predecessor or successor is in the filtered set.
      // Keeps untouched activities' baseline relationships intact.
      List<BaselineRelationship> existing =
          baselineRelationshipRepository.findByBaselineId(baselineId);
      List<BaselineRelationship> toRemove = existing.stream()
          .filter(br -> filteredIds.contains(br.getPredecessorActivityId())
              || filteredIds.contains(br.getSuccessorActivityId()))
          .toList();
      baselineRelationshipRepository.deleteAll(toRemove);
      List<ActivityRelationship> liveRelationships =
          activityRelationshipRepository.findByProjectId(projectId).stream()
              .filter(r -> filteredIds.contains(r.getPredecessorActivityId())
                  || filteredIds.contains(r.getSuccessorActivityId()))
              .toList();
      for (ActivityRelationship rel : liveRelationships) {
        BaselineRelationship br = new BaselineRelationship();
        br.setBaselineId(baselineId);
        br.setPredecessorActivityId(rel.getPredecessorActivityId());
        br.setSuccessorActivityId(rel.getSuccessorActivityId());
        br.setRelationshipType(rel.getRelationshipType() != null
            ? rel.getRelationshipType().name() : null);
        br.setLag(rel.getLag());
        baselineRelationshipRepository.save(br);
      }
    }

    auditService.logUpdate("Baseline", baselineId, "selectiveUpdate", null,
        "matched=" + filtered.size() + ", updated=" + updated + ", inserted=" + inserted);
    return BaselineResponse.from(baseline);
  }

  private boolean matchesFilter(Activity a, UpdateBaselineRequest request) {
    if (request.activityIds() != null && !request.activityIds().isEmpty()
        && !request.activityIds().contains(a.getId())) {
      return false;
    }
    if (Boolean.TRUE.equals(request.criticalOnly())
        && !Boolean.TRUE.equals(a.getIsCritical())) {
      return false;
    }
    if (Boolean.TRUE.equals(request.milestonesOnly())) {
      String type = a.getActivityType() != null ? a.getActivityType().name() : "";
      if (!"START_MILESTONE".equals(type) && !"FINISH_MILESTONE".equals(type)) return false;
    }
    if (request.statuses() != null && !request.statuses().isEmpty()) {
      String status = a.getStatus() != null ? a.getStatus().name() : "";
      if (!request.statuses().contains(status)) return false;
    }
    if (request.plannedStartFrom() != null
        && (a.getPlannedStartDate() == null
            || a.getPlannedStartDate().isBefore(request.plannedStartFrom()))) {
      return false;
    }
    if (request.plannedStartTo() != null
        && (a.getPlannedStartDate() == null
            || a.getPlannedStartDate().isAfter(request.plannedStartTo()))) {
      return false;
    }
    return true;
  }

  private RelationshipType parseRelationshipType(String name) {
    if (name == null) return RelationshipType.FINISH_TO_START;
    try {
      return RelationshipType.valueOf(name);
    } catch (IllegalArgumentException ex) {
      return RelationshipType.FINISH_TO_START;
    }
  }

  @Transactional
  public void deleteBaseline(UUID baselineId) {
    Baseline baseline =
        baselineRepository
            .findById(baselineId)
            .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));

    baselineActivityRepository.deleteAll(
        baselineActivityRepository.findByBaselineId(baselineId));
    baselineRelationshipRepository.deleteAll(
        baselineRelationshipRepository.findByBaselineId(baselineId));
    // Phase 5: cascade-clean the snapshot completeness tables too.
    baselineWbsRepository.deleteByBaselineId(baselineId);
    baselineResourceAssignmentRepository.deleteByBaselineId(baselineId);
    baselineExpenseRepository.deleteByBaselineId(baselineId);
    baselineRepository.delete(baseline);
    auditService.logDelete("Baseline", baselineId);
  }

  public List<BaselineVarianceResponse> getVariance(UUID projectId, UUID baselineId) {
    Baseline baseline =
        baselineRepository
            .findById(baselineId)
            .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));

    if (!baseline.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Baseline", baselineId);
    }

    List<BaselineActivity> baselineActivities =
        baselineActivityRepository.findByBaselineId(baselineId);

    // Load current activities and cost data
    List<Activity> currentActivities = activityRepository.findByProjectId(projectId);
    Map<UUID, Activity> activityMap = currentActivities.stream()
        .collect(Collectors.toMap(Activity::getId, a -> a));

    List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
    Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
        .filter(e -> e.getActivityId() != null)
        .collect(Collectors.groupingBy(ActivityExpense::getActivityId));

    List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
    Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
        .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));

    return baselineActivities.stream()
        .map(ba -> calculateVariance(ba, activityMap, expensesByActivity, assignmentsByActivity))
        .toList();
  }

  private BaselineVarianceResponse calculateVariance(
      BaselineActivity baselineActivity,
      Map<UUID, Activity> currentActivityMap,
      Map<UUID, List<ActivityExpense>> expensesByActivity,
      Map<UUID, List<ResourceAssignment>> assignmentsByActivity) {

    Activity currentActivity = currentActivityMap.get(baselineActivity.getActivityId());
    String activityName = currentActivity != null ? currentActivity.getName() : "Deleted Activity";

    Long startVarianceDays = 0L;
    Long finishVarianceDays = 0L;
    Double durationVariance = 0.0;
    BigDecimal costVariance = BigDecimal.ZERO;

    if (currentActivity != null) {
      // Schedule variance (positive = delayed)
      if (baselineActivity.getEarlyStart() != null && currentActivity.getPlannedStartDate() != null) {
        startVarianceDays = ChronoUnit.DAYS.between(
            baselineActivity.getEarlyStart(),
            currentActivity.getPlannedStartDate());
      }
      if (baselineActivity.getEarlyFinish() != null && currentActivity.getPlannedFinishDate() != null) {
        finishVarianceDays = ChronoUnit.DAYS.between(
            baselineActivity.getEarlyFinish(),
            currentActivity.getPlannedFinishDate());
      }

      // Duration variance
      if (baselineActivity.getOriginalDuration() != null && currentActivity.getOriginalDuration() != null) {
        durationVariance = currentActivity.getOriginalDuration() - baselineActivity.getOriginalDuration();
      }

      // Cost variance = current actual cost - baseline planned cost
      BigDecimal currentActualCost = ActivityCostCalculator.calculateActualCost(
          currentActivity.getId(), expensesByActivity, assignmentsByActivity);
      BigDecimal baselinePlannedCost = baselineActivity.getPlannedCost() != null
          ? baselineActivity.getPlannedCost() : BigDecimal.ZERO;
      costVariance = currentActualCost.subtract(baselinePlannedCost);
    }

    return new BaselineVarianceResponse(
        baselineActivity.getActivityId(),
        activityName,
        startVarianceDays,
        finishVarianceDays,
        durationVariance,
        costVariance);
  }

  public List<ScheduleComparisonResponse> getScheduleComparison(UUID projectId, UUID baselineId) {
    Baseline baseline = baselineRepository
        .findById(baselineId)
        .orElseThrow(() -> new ResourceNotFoundException("Baseline", baselineId));

    if (!baseline.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Baseline", baselineId);
    }

    List<BaselineActivity> baselineActivities = baselineActivityRepository.findByBaselineId(baselineId);
    Map<UUID, BaselineActivity> baselineActivityMap = baselineActivities.stream()
        .collect(Collectors.toMap(BaselineActivity::getActivityId, a -> a));

    List<Activity> currentActivities = activityRepository.findByProjectId(projectId);

    // Build comparison for current activities
    List<ScheduleComparisonResponse> comparisons = currentActivities.stream()
        .map(current -> compareActivity(current, baselineActivityMap.get(current.getId())))
        .collect(Collectors.toList());

    // Add DELETED entries for baseline activities not in current set
    Map<UUID, Activity> currentMap = currentActivities.stream()
        .collect(Collectors.toMap(Activity::getId, a -> a));
    for (BaselineActivity ba : baselineActivities) {
      if (!currentMap.containsKey(ba.getActivityId())) {
        comparisons.add(new ScheduleComparisonResponse(
            ba.getActivityId(),
            "Deleted Activity",
            null,
            ba.getEarlyStart(),
            0L,
            null,
            ba.getEarlyFinish(),
            0L,
            ScheduleComparisonResponse.ComparisonStatus.DELETED));
      }
    }

    return comparisons;
  }

  private ScheduleComparisonResponse compareActivity(Activity current, BaselineActivity baseline) {
    ScheduleComparisonResponse.ComparisonStatus status;
    LocalDate currentStart = current.getPlannedStartDate();
    LocalDate baselineStart = baseline != null ? baseline.getEarlyStart() : null;
    LocalDate currentFinish = current.getPlannedFinishDate();
    LocalDate baselineFinish = baseline != null ? baseline.getEarlyFinish() : null;

    if (baseline == null) {
      status = ScheduleComparisonResponse.ComparisonStatus.ADDED;
    } else if (areDatesEqual(currentStart, baselineStart) && areDatesEqual(currentFinish, baselineFinish)) {
      status = ScheduleComparisonResponse.ComparisonStatus.UNCHANGED;
    } else {
      status = ScheduleComparisonResponse.ComparisonStatus.CHANGED;
    }

    Long startVariance = calculateDaysDifference(baselineStart, currentStart);
    Long finishVariance = calculateDaysDifference(baselineFinish, currentFinish);

    return new ScheduleComparisonResponse(
        current.getId(),
        current.getName(),
        currentStart,
        baselineStart,
        startVariance,
        currentFinish,
        baselineFinish,
        finishVariance,
        status);
  }

  private boolean areDatesEqual(LocalDate date1, LocalDate date2) {
    if (date1 == null && date2 == null) return true;
    return date1 != null && date1.equals(date2);
  }

  private Long calculateDaysDifference(LocalDate from, LocalDate to) {
    if (from == null || to == null) return 0L;
    return ChronoUnit.DAYS.between(from, to);
  }
}
