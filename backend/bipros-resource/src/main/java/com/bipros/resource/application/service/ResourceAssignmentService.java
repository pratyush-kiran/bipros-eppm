package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.AssignedResourcePickerOption;
import com.bipros.resource.application.dto.CreateResourceAssignmentRequest;
import com.bipros.resource.application.dto.ResourceAssignmentResponse;
import com.bipros.resource.application.dto.ResourceUsageEntry;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ResourceAssignmentService {

  private final ResourceAssignmentRepository assignmentRepository;
  private final ResourceRepository resourceRepository;
  private final ResourceRoleRepository roleRepository;
  private final ActivityRepository activityRepository;
  private final ProjectResourceService projectResourceService;
  private final RateSnapshotService rateSnapshotService;
  private final AuditService auditService;

  /**
   * Batch-hydrate resource + activity + role names onto a list of assignments in 3 queries total.
   * Also derives an "effective role" per assignment: the assignment's own roleId when set, otherwise
   * the role of the staffed resource. Surface for grouping in the UI without changing the meaning of
   * the original {@code roleId} field (which the staff/swap workflow still relies on).
   */
  private List<ResourceAssignmentResponse> hydrate(List<ResourceAssignment> assignments) {
    if (assignments.isEmpty()) return List.of();
    var resourceIds = assignments.stream()
        .map(ResourceAssignment::getResourceId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    var activityIds = assignments.stream().map(ResourceAssignment::getActivityId).distinct().toList();

    Map<UUID, Resource> resourceMap = resourceIds.isEmpty()
        ? Map.of()
        : resourceRepository.findAllById(resourceIds).stream()
            .collect(Collectors.toMap(Resource::getId, r -> r));

    // Roles needed: those explicitly set on assignments + those reachable via the staffed resource.
    // Calling getRole().getId() on a lazy proxy reads the FK without triggering a SQL fetch.
    var roleIds = java.util.stream.Stream.concat(
            assignments.stream().map(ResourceAssignment::getRoleId).filter(Objects::nonNull),
            resourceMap.values().stream()
                .map(Resource::getRole)
                .filter(Objects::nonNull)
                .map(ResourceRole::getId))
        .distinct()
        .toList();
    Map<UUID, ResourceRole> roleMap = roleIds.isEmpty()
        ? Map.of()
        : roleRepository.findAllById(roleIds).stream()
            .collect(Collectors.toMap(ResourceRole::getId, r -> r));

    Map<UUID, String> activityNames = activityRepository.findAllById(activityIds).stream()
        .collect(Collectors.toMap(Activity::getId, Activity::getName));

    return assignments.stream()
        .map(a -> {
          UUID effectiveRoleId = resolveEffectiveRoleId(a, resourceMap);
          ResourceRole effectiveRole = effectiveRoleId == null ? null : roleMap.get(effectiveRoleId);
          ResourceRole assignmentRole = a.getRoleId() == null ? null : roleMap.get(a.getRoleId());
          Resource resource = a.getResourceId() == null ? null : resourceMap.get(a.getResourceId());
          // Mirror the single-row hydrate() logic: prefer the resource's own unit (snapshotted
          // from its rate master) so equipment/material assignments surface "Day", "Bag", "Nos"
          // even when the role has no productivityUnit. Fall back to the role's productivityUnit
          // for role-only / unstaffed slots where no resource is yet attached.
          String unit = (resource != null && resource.getUnit() != null && !resource.getUnit().isBlank())
              ? resource.getUnit()
              : (effectiveRole == null ? null : effectiveRole.getProductivityUnit());
          return ResourceAssignmentResponse.from(a,
              resource == null ? null : resource.getName(),
              a.getActivityId() == null ? null : activityNames.get(a.getActivityId()),
              assignmentRole == null ? null : assignmentRole.getName(),
              effectiveRoleId,
              effectiveRole == null ? null : effectiveRole.getName(),
              unit);
        })
        .toList();
  }

  /**
   * {@code max(planned − actual, 0)} for units; returns null when planned is null (no info to
   * derive). Mirrors the SQL the daily-output rollup runs so create / update / staff / swap leave
   * the same value the rollup would produce after the first daily output is recorded.
   */
  private static Double remainingFromPlanned(Double planned, Double actual) {
    if (planned == null) return null;
    double a = actual == null ? 0.0 : actual;
    return Math.max(planned - a, 0.0);
  }

  private static BigDecimal remainingFromPlanned(BigDecimal planned, BigDecimal actual) {
    if (planned == null) return null;
    BigDecimal a = actual == null ? BigDecimal.ZERO : actual;
    BigDecimal diff = planned.subtract(a);
    return diff.signum() < 0 ? BigDecimal.ZERO : diff;
  }

  private UUID resolveEffectiveRoleId(ResourceAssignment a, Map<UUID, Resource> resourceMap) {
    if (a.getRoleId() != null) return a.getRoleId();
    if (a.getResourceId() == null) return null;
    Resource r = resourceMap.get(a.getResourceId());
    if (r == null || r.getRole() == null) return null;
    return r.getRole().getId();
  }

  /**
   * Two-tier cost-resolution chain: {@code ProjectResource.rateOverride} → {@code Resource.costPerUnit} → null.
   * The simpler model intentionally drops the period-aware {@code ResourceRate} tier — Bipros doesn't
   * actively use rate cards today, and a single rate per resource matches the user's mental model.
   * If time-varying rates become a need later, re-introduce the {@code ResourceRate} tier deliberately.
   */
  private BigDecimal computePlannedCost(UUID projectId, UUID resourceId, Double plannedUnits) {
    if (plannedUnits == null) return null;

    BigDecimal rateOverride = projectResourceService.resolveRateOverride(projectId, resourceId);
    if (rateOverride != null) {
      return rateOverride.multiply(BigDecimal.valueOf(plannedUnits));
    }

    Resource resource = resourceRepository.findById(resourceId).orElse(null);
    if (resource != null && resource.getCostPerUnit() != null) {
      return resource.getCostPerUnit().multiply(BigDecimal.valueOf(plannedUnits));
    }
    return null;
  }

  private ResourceAssignmentResponse hydrate(ResourceAssignment a) {
    Resource resource = a.getResourceId() != null
        ? resourceRepository.findById(a.getResourceId()).orElse(null)
        : null;
    String rn = resource != null ? resource.getName() : null;
    String an = activityRepository.findById(a.getActivityId()).map(Activity::getName).orElse(null);
    String ron = a.getRoleId() != null
        ? roleRepository.findById(a.getRoleId()).map(ResourceRole::getName).orElse(null)
        : null;

    UUID effectiveRoleId = a.getRoleId() != null
        ? a.getRoleId()
        : (resource != null && resource.getRole() != null ? resource.getRole().getId() : null);
    ResourceRole effectiveRole = effectiveRoleId == null
        ? null
        : roleRepository.findById(effectiveRoleId).orElse(null);
    String effectiveRoleName = effectiveRole == null ? null : effectiveRole.getName();
    // Prefer the resource's own unit (snapshotted from its rate master). This way the assignment
    // grid auto-reflects rate-master unit changes via the existing Phase 3 sync chain. Fall back
    // to the role's productivityUnit only when there's no resource (role-only / unstaffed slots).
    String unit = (resource != null && resource.getUnit() != null && !resource.getUnit().isBlank())
        ? resource.getUnit()
        : (effectiveRole == null ? null : effectiveRole.getProductivityUnit());

    return ResourceAssignmentResponse.from(a, rn, an, ron, effectiveRoleId, effectiveRoleName, unit);
  }

  public ResourceAssignmentResponse assignResource(CreateResourceAssignmentRequest request) {
    log.info("Assigning resource: activityId={}, resourceId={}, roleId={}, projectId={}",
        request.activityId(), request.resourceId(), request.roleId(), request.projectId());

    if (request.resourceId() == null && request.roleId() == null) {
      throw new BusinessRuleException("ASSIGNMENT_TARGET_REQUIRED",
          "Either roleId or resourceId is required");
    }

    if (request.resourceId() != null && !resourceRepository.existsById(request.resourceId())) {
      throw new ResourceNotFoundException("Resource", request.resourceId());
    }

    if (request.resourceId() != null && !projectResourceService.isInPool(request.projectId(), request.resourceId())) {
      throw new BusinessRuleException("RESOURCE_NOT_IN_POOL",
          "Resource is not in the project pool: projectId=" + request.projectId() +
          ", resourceId=" + request.resourceId());
    }

    if (request.roleId() != null && !roleRepository.existsById(request.roleId())) {
      throw new ResourceNotFoundException("ResourceRole", request.roleId());
    }

    if (request.resourceId() != null) {
      assignmentRepository.findByActivityId(request.activityId())
          .stream()
          .filter(a -> request.resourceId().equals(a.getResourceId()))
          .findFirst()
          .ifPresent(a -> {
            throw new BusinessRuleException(
                "DUPLICATE_ASSIGNMENT",
                "Resource already assigned to this activity: activityId=" + request.activityId() +
                ", resourceId=" + request.resourceId());
          });
    } else {
      assignmentRepository.findByActivityIdAndResourceIdIsNullAndRoleId(
              request.activityId(), request.roleId())
          .ifPresent(a -> {
            throw new BusinessRuleException(
                "DUPLICATE_ROLE_ASSIGNMENT",
                "Role-only slot already exists for this activity: activityId=" + request.activityId() +
                ", roleId=" + request.roleId());
          });
    }

    String effectiveRateType = request.rateType() != null ? request.rateType() : "STANDARD";

    LocalDate plannedStart = request.plannedStartDate();
    LocalDate plannedFinish = request.plannedFinishDate();
    if (plannedStart == null || plannedFinish == null) {
      Activity activity = activityRepository.findById(request.activityId()).orElse(null);
      if (activity != null) {
        if (plannedStart == null) plannedStart = activity.getPlannedStartDate();
        if (plannedFinish == null) plannedFinish = activity.getPlannedFinishDate();
      }
    }

    // Unstaffed role-only slots get null planned cost (honest "rate unknown until staffed").
    // Staffed slots resolve via the two-tier chain in computePlannedCost.
    BigDecimal plannedCost = request.resourceId() != null
        ? computePlannedCost(request.projectId(), request.resourceId(), request.plannedUnits())
        : null;

    // Initialize remaining = planned at creation time. Without this the columns sit at NULL until
    // the daily-output rollup runs, which makes role-grouped totals understate the true remaining
    // work. See DailyActivityResourceOutputService.recomputeAssignmentRollup for the running rule.
    ResourceAssignment assignment = ResourceAssignment.builder()
        .activityId(request.activityId())
        .resourceId(request.resourceId())
        .roleId(request.roleId())
        .projectId(request.projectId())
        .plannedUnits(request.plannedUnits())
        .remainingUnits(request.plannedUnits())
        // Phase 2: capture the original commitment at assignment creation. budgeted_* stays
        // frozen on subsequent re-plans; only the explicit "Re-budget" action below copies
        // current planned → budgeted again.
        .budgetedUnits(request.plannedUnits())
        .budgetedCost(plannedCost)
        .rateType(effectiveRateType)
        .resourceCurveId(request.resourceCurveId())
        .plannedStartDate(plannedStart)
        .plannedFinishDate(plannedFinish)
        .plannedCost(plannedCost)
        .remainingCost(plannedCost)
        .build();

    ResourceAssignment saved = assignmentRepository.save(assignment);
    log.info("Resource assignment created: id={}", saved.getId());

    ResourceAssignmentResponse response = hydrate(saved);
    auditService.logCreate("ResourceAssignment", saved.getId(), response);
    return response;
  }

  public ResourceAssignmentResponse staffAssignment(UUID assignmentId, UUID resourceId, boolean override) {
    log.info("Staffing assignment: assignmentId={}, resourceId={}, override={}", assignmentId, resourceId, override);

    ResourceAssignment assignment = assignmentRepository.findById(assignmentId)
        .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", assignmentId));

    if (assignment.getResourceId() != null) {
      throw new BusinessRuleException("ALREADY_STAFFED",
          "Assignment is already staffed: assignmentId=" + assignmentId);
    }
    if (assignment.getRoleId() == null) {
      throw new BusinessRuleException("NO_ROLE_TO_STAFF",
          "Assignment has no role to staff against: assignmentId=" + assignmentId);
    }

    Resource resource = resourceRepository.findById(resourceId)
        .orElseThrow(() -> new ResourceNotFoundException("Resource", resourceId));

    if (!projectResourceService.isInPool(assignment.getProjectId(), resourceId)) {
      throw new BusinessRuleException("RESOURCE_NOT_IN_POOL",
          "Resource is not in the project pool: projectId=" + assignment.getProjectId() +
          ", resourceId=" + resourceId);
    }

    // Eligibility: resource's role must match assignment role (the new model has 1 role per resource)
    if (resource.getRole() == null || !assignment.getRoleId().equals(resource.getRole().getId())) {
      if (!override) {
        throw new BusinessRuleException("RESOURCE_NOT_QUALIFIED",
            "Resource's role does not match the required assignment role: resourceId=" + resourceId
                + ", roleId=" + assignment.getRoleId());
      }
      var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      boolean isAdmin = auth != null && auth.getAuthorities().stream()
          .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
      if (!isAdmin) {
        throw new BusinessRuleException("STAFF_OVERRIDE_NOT_AUTHORIZED",
            "Only admins can override qualification checks");
      }
    }

    assignmentRepository.findByActivityId(assignment.getActivityId())
        .stream()
        .filter(a -> resourceId.equals(a.getResourceId()))
        .findFirst()
        .ifPresent(a -> {
          throw new BusinessRuleException(
              "DUPLICATE_ASSIGNMENT",
              "Resource already assigned to this activity: activityId=" + assignment.getActivityId() +
              ", resourceId=" + resourceId);
        });

    assignment.setResourceId(resourceId);
    BigDecimal plannedCost = computePlannedCost(assignment.getProjectId(), resourceId, assignment.getPlannedUnits());
    assignment.setPlannedCost(plannedCost);
    assignment.setRemainingCost(remainingFromPlanned(plannedCost, assignment.getActualCost()));
    // Role-only assignments are created with null planned_cost (rate unknown until staffed).
    // The first staffing event is therefore also when the budget is first computable, so
    // capture it as budgeted_cost too. Subsequent swaps do NOT update budgeted (the original
    // commitment is what's frozen).
    if (assignment.getBudgetedUnits() == null) {
      assignment.setBudgetedUnits(assignment.getPlannedUnits());
    }
    if (assignment.getBudgetedCost() == null) {
      assignment.setBudgetedCost(plannedCost);
    }

    ResourceAssignment saved = assignmentRepository.save(assignment);
    log.info("Assignment staffed: id={}, resourceId={}", saved.getId(), resourceId);

    ResourceAssignmentResponse response = hydrate(saved);
    auditService.logUpdate("ResourceAssignment", saved.getId(), "staff", assignment, response);
    return response;
  }

  public ResourceAssignmentResponse swapResource(UUID assignmentId, UUID newResourceId, boolean override) {
    log.info("Swapping resource: assignmentId={}, newResourceId={}, override={}", assignmentId, newResourceId, override);

    ResourceAssignment assignment = assignmentRepository.findById(assignmentId)
        .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", assignmentId));

    if (assignment.getResourceId() == null) {
      throw new BusinessRuleException("NOT_STAFFED",
          "Assignment is not staffed yet; use staff endpoint instead: assignmentId=" + assignmentId);
    }
    if (assignment.getRoleId() == null) {
      throw new BusinessRuleException("NO_ROLE",
          "Assignment has no role; direct resource swap is not supported: assignmentId=" + assignmentId);
    }

    Resource resource = resourceRepository.findById(newResourceId)
        .orElseThrow(() -> new ResourceNotFoundException("Resource", newResourceId));

    if (!projectResourceService.isInPool(assignment.getProjectId(), newResourceId)) {
      throw new BusinessRuleException("RESOURCE_NOT_IN_POOL",
          "Resource is not in the project pool: projectId=" + assignment.getProjectId() +
          ", resourceId=" + newResourceId);
    }

    if (resource.getRole() == null || !assignment.getRoleId().equals(resource.getRole().getId())) {
      if (!override) {
        throw new BusinessRuleException("RESOURCE_NOT_QUALIFIED",
            "Resource's role does not match the required assignment role: resourceId=" + newResourceId
                + ", roleId=" + assignment.getRoleId());
      }
      var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      boolean isAdmin = auth != null && auth.getAuthorities().stream()
          .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
      if (!isAdmin) {
        throw new BusinessRuleException("SWAP_OVERRIDE_NOT_AUTHORIZED",
            "Only admins can override qualification checks");
      }
    }

    assignmentRepository.findByActivityId(assignment.getActivityId())
        .stream()
        .filter(a -> newResourceId.equals(a.getResourceId()) && !a.getId().equals(assignmentId))
        .findFirst()
        .ifPresent(a -> {
          throw new BusinessRuleException(
              "DUPLICATE_ASSIGNMENT",
              "Resource already assigned to this activity: activityId=" + assignment.getActivityId() +
              ", resourceId=" + newResourceId);
        });

    assignment.setResourceId(newResourceId);
    BigDecimal plannedCost = computePlannedCost(assignment.getProjectId(), newResourceId, assignment.getPlannedUnits());
    assignment.setPlannedCost(plannedCost);
    assignment.setRemainingCost(remainingFromPlanned(plannedCost, assignment.getActualCost()));

    ResourceAssignment saved = assignmentRepository.save(assignment);
    log.info("Resource swapped: id={}, newResourceId={}", saved.getId(), newResourceId);

    ResourceAssignmentResponse response = hydrate(saved);
    auditService.logUpdate("ResourceAssignment", saved.getId(), "swap", assignment, response);
    return response;
  }

  public ResourceAssignmentResponse getAssignment(UUID id) {
    ResourceAssignment assignment = assignmentRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", id));
    return hydrate(assignment);
  }

  public List<ResourceAssignmentResponse> getAssignmentsByActivity(UUID activityId) {
    return hydrate(assignmentRepository.findByActivityId(activityId));
  }

  /**
   * Picker-mode lookup for the DPR drawer. Returns one option per staffed resource assigned to
   * {@code activityId}, optionally filtered by {@code kind} (MANPOWER / EQUIPMENT / MATERIAL),
   * with a rate snapshot resolved at {@code reportDate}.
   *
   * <p>Role-only assignments (no {@code resource_id}) are excluded — the supervisor needs to pick
   * a concrete resource for the cost snapshot to make sense.
   *
   * <p>{@code MANPOWER} maps to the seeded resource type code {@code LABOR}.
   */
  @Transactional(readOnly = true)
  public List<AssignedResourcePickerOption> getPickerOptionsByActivity(
      UUID activityId, String kind, LocalDate reportDate) {
    List<ResourceAssignment> assignments = assignmentRepository.findByActivityId(activityId);
    if (assignments.isEmpty()) return List.of();

    var resourceIds = assignments.stream()
        .map(ResourceAssignment::getResourceId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (resourceIds.isEmpty()) return List.of();

    Map<UUID, Resource> resourceMap = resourceRepository.findAllById(resourceIds).stream()
        .collect(Collectors.toMap(Resource::getId, r -> r));

    String requestedTypeCode = mapKindToResourceTypeCode(kind);
    LocalDate effectiveOn = reportDate != null ? reportDate : LocalDate.now();

    return assignments.stream()
        .filter(a -> a.getResourceId() != null)
        .map(a -> {
          Resource resource = resourceMap.get(a.getResourceId());
          if (resource == null) return null;
          String typeCode = resource.getResourceType() == null ? null : resource.getResourceType().getCode();
          if (requestedTypeCode != null && !requestedTypeCode.equalsIgnoreCase(typeCode)) {
            return null;
          }
          RateSnapshotService.RateSnapshot snap = rateSnapshotService.resolve(a, effectiveOn);
          return new AssignedResourcePickerOption(
              a.getId(),
              resource.getId(),
              resource.getName(),
              resource.getCode(),
              resource.getUnit(),
              snap.unitRateBasis(),
              snap.unitRate(),
              a.getRateType(),
              a.getPlannedUnits(),
              a.getActualUnits(),
              a.getPlannedCost(),
              a.getActualCost(),
              resourceTypeCodeToKind(typeCode));
        })
        .filter(Objects::nonNull)
        .toList();
  }

  /** UI-facing kind ↔ seeded ResourceType code mapping. {@code MANPOWER} ↔ {@code LABOR}. */
  static String mapKindToResourceTypeCode(String kind) {
    if (kind == null || kind.isBlank()) return null;
    String k = kind.trim().toUpperCase();
    return switch (k) {
      case "MANPOWER", "LABOR", "LABOUR" -> "LABOR";
      case "EQUIPMENT" -> "EQUIPMENT";
      case "MATERIAL" -> "MATERIAL";
      default -> k;
    };
  }

  static String resourceTypeCodeToKind(String typeCode) {
    if (typeCode == null) return null;
    return "LABOR".equalsIgnoreCase(typeCode) ? "MANPOWER" : typeCode.toUpperCase();
  }

  public List<ResourceAssignmentResponse> getAssignmentsByResource(UUID resourceId) {
    if (!resourceRepository.existsById(resourceId)) {
      throw new ResourceNotFoundException("Resource", resourceId);
    }
    return hydrate(assignmentRepository.findByResourceId(resourceId));
  }

  public List<ResourceAssignmentResponse> getAssignmentsByProject(UUID projectId) {
    return hydrate(assignmentRepository.findByProjectId(projectId));
  }

  public ResourceAssignmentResponse updateAssignment(UUID id, CreateResourceAssignmentRequest request) {
    ResourceAssignment assignment = assignmentRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", id));

    boolean wasStaffed = assignment.getResourceId() != null;
    boolean willBeStaffed = request.resourceId() != null;
    if (wasStaffed != willBeStaffed) {
      throw new BusinessRuleException("STAFFED_STATE_CHANGE_NOT_ALLOWED",
          "Use /staff or /swap endpoints to change the staffed state of an assignment");
    }

    if (request.resourceId() == null && request.roleId() == null) {
      throw new BusinessRuleException("ASSIGNMENT_TARGET_REQUIRED",
          "Either roleId or resourceId is required");
    }

    if (request.resourceId() != null && !projectResourceService.isInPool(request.projectId(), request.resourceId())) {
      throw new BusinessRuleException("RESOURCE_NOT_IN_POOL",
          "Resource is not in the project pool: projectId=" + request.projectId() +
          ", resourceId=" + request.resourceId());
    }

    if (request.resourceId() != null
        && !request.resourceId().equals(assignment.getResourceId())) {
      assignmentRepository.findByActivityId(request.activityId())
          .stream()
          .filter(a -> request.resourceId().equals(a.getResourceId()) && !a.getId().equals(id))
          .findFirst()
          .ifPresent(a -> {
            throw new BusinessRuleException(
                "DUPLICATE_ASSIGNMENT",
                "Resource already assigned to this activity: activityId=" + request.activityId() +
                ", resourceId=" + request.resourceId());
          });
    }
    if (request.resourceId() == null
        && request.roleId() != null
        && !request.roleId().equals(assignment.getRoleId())) {
      assignmentRepository.findByActivityIdAndResourceIdIsNullAndRoleId(
              request.activityId(), request.roleId())
          .ifPresent(a -> {
            throw new BusinessRuleException(
                "DUPLICATE_ROLE_ASSIGNMENT",
                "Role-only slot already exists for this activity: activityId=" + request.activityId() +
                ", roleId=" + request.roleId());
          });
    }

    assignment.setActivityId(request.activityId());
    assignment.setResourceId(request.resourceId());
    assignment.setRoleId(request.roleId());
    assignment.setProjectId(request.projectId());
    assignment.setPlannedUnits(request.plannedUnits());
    assignment.setRemainingUnits(remainingFromPlanned(request.plannedUnits(), assignment.getActualUnits()));
    assignment.setRateType(request.rateType());
    assignment.setResourceCurveId(request.resourceCurveId());
    assignment.setPlannedStartDate(request.plannedStartDate());
    assignment.setPlannedFinishDate(request.plannedFinishDate());

    // Unstaffed role-only slots get null planned cost; staffed slots resolve via the two-tier chain.
    BigDecimal plannedCost = request.resourceId() != null
        ? computePlannedCost(request.projectId(), request.resourceId(), request.plannedUnits())
        : null;
    assignment.setPlannedCost(plannedCost);
    assignment.setRemainingCost(remainingFromPlanned(plannedCost, assignment.getActualCost()));

    ResourceAssignment updated = assignmentRepository.save(assignment);
    log.info("Resource assignment updated: id={}", id);

    ResourceAssignmentResponse response = hydrate(updated);
    auditService.logUpdate("ResourceAssignment", id, "assignment", assignment, response);
    return response;
  }

  /**
   * Recomputes planned/actual/remaining/at-completion cost on every {@link ResourceAssignment}
   * referencing a given resource, across every project. Called after a Resource's
   * {@code costPerUnit} or {@code unit} changes — directly, or via {@code RateMasterSyncService}
   * when an upstream rate-master row is edited.
   *
   * <p>The two-tier resolution chain in {@link #computePlannedCost} is honoured: assignments
   * whose project pool entry has a {@code rateOverride} keep that override (the recompute
   * resolves to the same value). Only assignments that fall back to {@code Resource.costPerUnit}
   * actually change.
   */
  public int recomputeAssignmentsForResource(UUID resourceId) {
    if (resourceId == null) return 0;
    List<ResourceAssignment> assignments = assignmentRepository.findByResourceId(resourceId);
    int updated = 0;
    for (ResourceAssignment a : assignments) {
      BigDecimal newPlanned = computePlannedCost(a.getProjectId(), resourceId, a.getPlannedUnits());
      BigDecimal actualRate = resolveActualRate(a.getProjectId(), resourceId);
      BigDecimal newActual = (actualRate != null && a.getActualUnits() != null)
          ? actualRate.multiply(BigDecimal.valueOf(a.getActualUnits()))
          : null;
      BigDecimal newRemaining = (actualRate != null && a.getRemainingUnits() != null)
          ? actualRate.multiply(BigDecimal.valueOf(a.getRemainingUnits()))
          : null;
      BigDecimal newEac = (newActual != null && newRemaining != null)
          ? newActual.add(newRemaining)
          : newActual != null ? newActual : newRemaining;

      boolean changed = !Objects.equals(newPlanned, a.getPlannedCost())
          || !Objects.equals(newActual, a.getActualCost())
          || !Objects.equals(newRemaining, a.getRemainingCost())
          || !Objects.equals(newEac, a.getAtCompletionCost());
      if (changed) {
        a.setPlannedCost(newPlanned);
        a.setActualCost(newActual);
        a.setRemainingCost(newRemaining);
        a.setAtCompletionCost(newEac);
        assignmentRepository.save(a);
        updated++;
      }
    }
    if (updated > 0) {
      log.info("Resource cost recompute: resourceId={}, assignmentsUpdated={}", resourceId, updated);
    }
    return updated;
  }

  public int recomputeProjectCosts(UUID projectId) {
    log.info("Recomputing costs for project: projectId={}", projectId);
    List<ResourceAssignment> assignments = assignmentRepository.findByProjectId(projectId);
    int updated = 0;
    for (ResourceAssignment a : assignments) {
      // Two paths:
      //   (1) Legacy resource-based assignment (resource_id != null): rate from
      //       ProjectResource.rateOverride → Resource.costPerUnit.
      //   (2) Role-based assignment (resource_id null, role_id + variant FK + effective_rate set):
      //       use the snapshotted effective_rate. Without this fallback, role-only rows would have
      //       their planned_cost / actual_cost wiped to null on every Recompute click.
      BigDecimal rate;
      if (a.getResourceId() != null) {
        rate = resolveActualRate(projectId, a.getResourceId());
      } else {
        rate = a.getEffectiveRate();
      }

      // Role-based rows: planned cost is headcount × rate (manpower/equipment) or
      // quantity × rate (material) — duration is not multiplied in. Legacy resource-based
      // rows keep rate × plannedUnits.
      BigDecimal newPlanned;
      if (rate == null) {
        newPlanned = a.getPlannedCost();
      } else if (a.getResourceId() == null) {
        if (a.getHeadcount() != null) {
          newPlanned = rate.multiply(BigDecimal.valueOf(a.getHeadcount()));
        } else if (a.getQuantity() != null) {
          newPlanned = rate.multiply(a.getQuantity());
        } else {
          newPlanned = a.getPlannedCost();
        }
      } else if (a.getPlannedUnits() != null) {
        newPlanned = rate.multiply(BigDecimal.valueOf(a.getPlannedUnits()));
      } else {
        newPlanned = a.getPlannedCost();
      }
      BigDecimal newActual = (rate != null && a.getActualUnits() != null)
          ? rate.multiply(BigDecimal.valueOf(a.getActualUnits()))
          : null;
      BigDecimal newRemaining = (rate != null && a.getRemainingUnits() != null)
          ? rate.multiply(BigDecimal.valueOf(a.getRemainingUnits()))
          : null;
      BigDecimal newEac = (newActual != null && newRemaining != null)
          ? newActual.add(newRemaining)
          : newActual != null ? newActual : newRemaining;

      boolean changed = !Objects.equals(newPlanned, a.getPlannedCost())
          || !Objects.equals(newActual, a.getActualCost())
          || !Objects.equals(newRemaining, a.getRemainingCost())
          || !Objects.equals(newEac, a.getAtCompletionCost());
      if (changed) {
        a.setPlannedCost(newPlanned);
        a.setActualCost(newActual);
        a.setRemainingCost(newRemaining);
        a.setAtCompletionCost(newEac);
        assignmentRepository.save(a);
        updated++;
      }
    }
    log.info("Project cost recompute complete: projectId={}, updated={}", projectId, updated);
    return updated;
  }

  /**
   * Two-tier rate resolution mirroring {@link #computePlannedCost(UUID, UUID, Double)}:
   * project pool override → resource's costPerUnit → null.
   */
  private BigDecimal resolveActualRate(UUID projectId, UUID resourceId) {
    BigDecimal rateOverride = projectResourceService.resolveRateOverride(projectId, resourceId);
    if (rateOverride != null) return rateOverride;

    Resource resource = resourceRepository.findById(resourceId).orElse(null);
    return resource == null ? null : resource.getCostPerUnit();
  }

  /**
   * Phase 2 of the baseline-progress roadmap: explicit "Re-budget" action. Copies the current
   * {@code plannedUnits} / {@code plannedCost} into {@code budgetedUnits} / {@code budgetedCost}.
   * Audit-logged so the history of budget changes is queryable. Should only be invoked on a
   * deliberate planner action — never as a side-effect of plan edits.
   */
  public ResourceAssignmentResponse rebudgetAssignment(UUID assignmentId) {
    ResourceAssignment assignment = assignmentRepository.findById(assignmentId)
        .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", assignmentId));

    Double oldBudgetedUnits = assignment.getBudgetedUnits();
    BigDecimal oldBudgetedCost = assignment.getBudgetedCost();

    assignment.setBudgetedUnits(assignment.getPlannedUnits());
    assignment.setBudgetedCost(assignment.getPlannedCost());

    ResourceAssignment saved = assignmentRepository.save(assignment);
    log.info(
        "Resource assignment re-budgeted: id={}, units {} -> {}, cost {} -> {}",
        assignmentId,
        oldBudgetedUnits,
        saved.getBudgetedUnits(),
        oldBudgetedCost,
        saved.getBudgetedCost());

    ResourceAssignmentResponse response = hydrate(saved);
    auditService.logUpdate("ResourceAssignment", assignmentId, "rebudget", assignment, response);
    return response;
  }

  public void removeAssignment(UUID assignmentId) {
    if (!assignmentRepository.existsById(assignmentId)) {
      throw new ResourceNotFoundException("ResourceAssignment", assignmentId);
    }
    assignmentRepository.deleteById(assignmentId);
    auditService.logDelete("ResourceAssignment", assignmentId);
  }

  public List<ResourceUsageEntry> getResourceUsageProfile(UUID resourceId, LocalDate startDate, LocalDate endDate) {
    if (!resourceRepository.existsById(resourceId)) {
      throw new ResourceNotFoundException("Resource", resourceId);
    }

    Resource resource = resourceRepository.findById(resourceId).orElseThrow();
    List<ResourceAssignment> assignments =
        assignmentRepository.findByResourceIdAndPlannedStartDateBetween(resourceId, startDate, endDate);

    TreeMap<LocalDate, Double> plannedUsage = new TreeMap<>();
    TreeMap<LocalDate, Double> actualUsage = new TreeMap<>();

    for (ResourceAssignment assignment : assignments) {
      LocalDate assignStart = assignment.getPlannedStartDate();
      LocalDate assignEnd = assignment.getPlannedFinishDate();

      if (assignStart != null && assignEnd != null) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(assignStart, assignEnd) + 1;
        if (days > 0 && assignment.getPlannedUnits() != null) {
          double unitsPerDay = assignment.getPlannedUnits() / days;
          LocalDate current = assignStart;
          while (!current.isAfter(assignEnd)) {
            if (!current.isBefore(startDate) && !current.isAfter(endDate)) {
              plannedUsage.merge(current, unitsPerDay, Double::sum);
            }
            current = current.plusDays(1);
          }
        }
      }

      if (assignment.getActualStartDate() != null && assignment.getActualFinishDate() != null) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(
            assignment.getActualStartDate(), assignment.getActualFinishDate()) + 1;
        if (days > 0 && assignment.getActualUnits() != null) {
          double unitsPerDay = assignment.getActualUnits() / days;
          LocalDate current = assignment.getActualStartDate();
          while (!current.isAfter(assignment.getActualFinishDate())) {
            if (!current.isBefore(startDate) && !current.isAfter(endDate)) {
              actualUsage.merge(current, unitsPerDay, Double::sum);
            }
            current = current.plusDays(1);
          }
        }
      }
    }

    List<ResourceUsageEntry> entries = new ArrayList<>();
    LocalDate current = startDate;
    while (!current.isAfter(endDate)) {
      entries.add(new ResourceUsageEntry(
          resourceId,
          resource.getName(),
          current,
          plannedUsage.getOrDefault(current, 0.0),
          actualUsage.getOrDefault(current, 0.0)
      ));
      current = current.plusDays(1);
    }

    return entries;
  }
}
