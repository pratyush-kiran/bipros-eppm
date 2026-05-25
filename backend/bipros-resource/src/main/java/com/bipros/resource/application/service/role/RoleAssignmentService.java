package com.bipros.resource.application.service.role;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.resource.application.dto.role.RoleAssignmentRequest;
import com.bipros.resource.application.dto.role.RoleAssignmentResponse;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Role-based activity demand service. New flow: activity demand is {@code role + variant +
 * headcount} (manpower / equipment, Option B — no duration multiplication) or
 * {@code role + variant + quantity} (material).
 * No Resource instance, no project pool — rates come from {@link RoleRateResolver}.
 *
 * <p>This service lives alongside (not inside) {@code ResourceAssignmentService} so the legacy
 * resource-instance path keeps working for historical data and the in-flight DPR / EVM paths
 * during migration. Both services write to the same {@code resource_assignments} table.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RoleAssignmentService {

  private final ResourceAssignmentRepository assignmentRepo;
  private final ResourceRoleRepository roleRepo;
  private final ActivityRepository activityRepo;
  private final ManpowerRoleRateRepository manpowerRepo;
  private final EquipmentRoleVariantRepository equipmentRepo;
  private final MaterialRoleVariantRepository materialRepo;
  private final ManpowerCategoryMasterRepository categoryRepo;
  private final GradeMasterRepository gradeRepo;
  private final RoleRateResolver rateResolver;

  public RoleAssignmentResponse createRoleAssignment(UUID projectId, RoleAssignmentRequest req) {
    assertActivityEditable(req.activityId());
    ResourceRole role =
        roleRepo
            .findById(req.roleId())
            .orElseThrow(() -> new ResourceNotFoundException("ResourceRole", req.roleId()));
    String typeCode = role.getResourceType().getCode().toUpperCase();
    UUID variantId = pickVariantId(typeCode, req);
    if (variantId == null) {
      throw new BusinessRuleException(
          "VARIANT_REQUIRED",
          "Variant must be set for role type " + typeCode);
    }

    BigDecimal rate = rateResolver.resolveRate(projectId, typeCode, variantId);
    if (rate == null) {
      throw new BusinessRuleException(
          "RATE_NOT_FOUND",
          "No rate available for variant " + variantId + " in project " + projectId);
    }
    String unit = rateResolver.resolveUnit(typeCode, variantId);

    // plannedUnits is the admin's typed headcount (or quantity for material) — total commitment
    // for this activity. We DO NOT multiply by duration; duration is a descriptive label on the
    // rate (Day/Hr/etc.) and is captured on the row for reporting only.
    Activity activity = activityRepo.findById(req.activityId()).orElse(null);
    BigDecimal plannedUnits;
    BigDecimal effectiveDuration = null;
    Integer headcount = req.headcount();
    BigDecimal quantity = req.quantity();
    if ("MATERIAL".equals(typeCode)) {
      if (quantity == null || quantity.signum() <= 0) {
        throw new BusinessRuleException("QUANTITY_REQUIRED", "Material role requires quantity > 0");
      }
      plannedUnits = quantity;
      headcount = null;
    } else {
      if (headcount == null || headcount <= 0) {
        throw new BusinessRuleException("HEADCOUNT_REQUIRED", "headcount must be > 0");
      }
      // effectiveDuration retained on the row for reporting only — does NOT multiply into units or cost.
      // Option B: plannedUnits is the admin's typed headcount (total commitment).
      effectiveDuration = resolveDuration(req.duration(), activity);
      plannedUnits = BigDecimal.valueOf(headcount);
      quantity = null;
    }
    // Cost: plannedCost = plannedUnits × rate. Uniform across manpower / equipment / material.
    BigDecimal plannedCost =
        "MATERIAL".equals(typeCode)
            ? quantity.multiply(rate)
            : BigDecimal.valueOf(headcount).multiply(rate);

    LocalDate plannedStart = req.plannedStartDate();
    LocalDate plannedFinish = req.plannedFinishDate();
    if (plannedStart == null || plannedFinish == null) {
      if (activity != null) {
        if (plannedStart == null) plannedStart = activity.getPlannedStartDate();
        if (plannedFinish == null) plannedFinish = activity.getPlannedFinishDate();
      }
    }

    // Dedup: same (activity, role, variant) merges into the existing row instead of
    // inserting a duplicate. Headcount/quantity is incremented and cost recomputed.
    Optional<ResourceAssignment> existing = findExisting(req.activityId(), req.roleId(), typeCode, variantId);
    if (existing.isPresent()) {
      ResourceAssignment merged = existing.get();
      BigDecimal mergedUnits;
      BigDecimal mergedCost;
      if ("MATERIAL".equals(typeCode)) {
        BigDecimal current = merged.getQuantity() == null ? BigDecimal.ZERO : merged.getQuantity();
        BigDecimal next = current.add(quantity);
        merged.setQuantity(next);
        mergedUnits = next;
        mergedCost = next.multiply(rate);
      } else {
        int current = merged.getHeadcount() == null ? 0 : merged.getHeadcount();
        int next = current + headcount;
        merged.setHeadcount(next);
        merged.setDuration(effectiveDuration);
        mergedUnits = BigDecimal.valueOf(next);
        mergedCost = BigDecimal.valueOf(next).multiply(rate);
      }
      merged.setPlannedUnits(mergedUnits.doubleValue());
      merged.setBudgetedUnits(mergedUnits.doubleValue());
      merged.setPlannedCost(mergedCost);
      merged.setBudgetedCost(mergedCost);
      BigDecimal actualCost = merged.getActualCost() == null ? BigDecimal.ZERO : merged.getActualCost();
      merged.setRemainingCost(mergedCost.subtract(actualCost).max(BigDecimal.ZERO));
      double actualUnits = merged.getActualUnits() == null ? 0.0 : merged.getActualUnits();
      merged.setRemainingUnits(Math.max(mergedUnits.doubleValue() - actualUnits, 0.0));
      merged.setEffectiveRate(rate);
      merged.setUnit(unit);
      ResourceAssignment saved = assignmentRepo.save(merged);
      log.info(
          "Role assignment merged: id={}, activity={}, role={}, variant={}, plannedUnits={}, plannedCost={}",
          saved.getId(), req.activityId(), req.roleId(), variantId, mergedUnits, mergedCost);
      return toResponse(saved, role, typeCode, variantId);
    }

    ResourceAssignment assignment =
        ResourceAssignment.builder()
            .activityId(req.activityId())
            .projectId(projectId)
            .roleId(req.roleId())
            .manpowerRoleRateId(
                "LABOR".equals(typeCode) || "MANPOWER".equals(typeCode) ? variantId : null)
            .equipmentRoleVariantId("EQUIPMENT".equals(typeCode) ? variantId : null)
            .materialRoleVariantId("MATERIAL".equals(typeCode) ? variantId : null)
            .headcount(headcount)
            .duration(effectiveDuration)
            .quantity(quantity)
            .plannedUnits(plannedUnits.doubleValue())
            .remainingUnits(plannedUnits.doubleValue())
            .actualUnits(0.0)
            .budgetedUnits(plannedUnits.doubleValue())
            .plannedCost(plannedCost)
            .budgetedCost(plannedCost)
            .remainingCost(plannedCost)
            .actualCost(BigDecimal.ZERO)
            .effectiveRate(rate)
            .unit(unit)
            .rateType(req.rateType() == null ? "STANDARD" : req.rateType())
            .plannedStartDate(plannedStart)
            .plannedFinishDate(plannedFinish)
            .build();

    ResourceAssignment saved = assignmentRepo.save(assignment);
    log.info(
        "Role assignment created: id={}, activity={}, role={}, variant={}, plannedUnits={}, plannedCost={}",
        saved.getId(),
        req.activityId(),
        req.roleId(),
        variantId,
        plannedUnits,
        plannedCost);
    return toResponse(saved, role, typeCode, variantId);
  }

  public RoleAssignmentResponse updateRoleAssignment(UUID assignmentId, RoleAssignmentRequest req) {
    ResourceAssignment a =
        assignmentRepo
            .findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", assignmentId));
    assertActivityEditable(a.getActivityId());
    ResourceRole role =
        roleRepo
            .findById(req.roleId())
            .orElseThrow(() -> new ResourceNotFoundException("ResourceRole", req.roleId()));
    String typeCode = role.getResourceType().getCode().toUpperCase();
    UUID variantId = pickVariantId(typeCode, req);
    BigDecimal rate = rateResolver.resolveRate(a.getProjectId(), typeCode, variantId);
    if (rate == null) {
      throw new BusinessRuleException("RATE_NOT_FOUND", "No rate for variant " + variantId);
    }
    String unit = rateResolver.resolveUnit(typeCode, variantId);

    Activity activity = activityRepo.findById(a.getActivityId()).orElse(null);
    BigDecimal plannedUnits;
    BigDecimal effectiveDuration = null;
    Integer headcount = req.headcount();
    BigDecimal quantity = req.quantity();
    if ("MATERIAL".equals(typeCode)) {
      if (quantity == null || quantity.signum() <= 0) {
        throw new BusinessRuleException("QUANTITY_REQUIRED", "Material role requires quantity > 0");
      }
      plannedUnits = quantity;
      headcount = null;
    } else {
      if (headcount == null || headcount <= 0) {
        throw new BusinessRuleException("HEADCOUNT_REQUIRED", "headcount must be > 0");
      }
      effectiveDuration = resolveDuration(req.duration(), activity);
      plannedUnits = BigDecimal.valueOf(headcount);
      quantity = null;
    }
    BigDecimal plannedCost =
        "MATERIAL".equals(typeCode)
            ? quantity.multiply(rate)
            : BigDecimal.valueOf(headcount).multiply(rate);

    a.setRoleId(req.roleId());
    a.setManpowerRoleRateId(
        "LABOR".equals(typeCode) || "MANPOWER".equals(typeCode) ? variantId : null);
    a.setEquipmentRoleVariantId("EQUIPMENT".equals(typeCode) ? variantId : null);
    a.setMaterialRoleVariantId("MATERIAL".equals(typeCode) ? variantId : null);
    a.setHeadcount(headcount);
    a.setDuration(effectiveDuration);
    a.setQuantity(quantity);
    a.setPlannedUnits(plannedUnits.doubleValue());
    a.setPlannedCost(plannedCost);
    BigDecimal actualCost = a.getActualCost() == null ? BigDecimal.ZERO : a.getActualCost();
    a.setRemainingCost(plannedCost.subtract(actualCost).max(BigDecimal.ZERO));
    double actualUnits = a.getActualUnits() == null ? 0.0 : a.getActualUnits();
    a.setRemainingUnits(Math.max(plannedUnits.doubleValue() - actualUnits, 0.0));
    a.setEffectiveRate(rate);
    a.setUnit(unit);
    if (req.plannedStartDate() != null) a.setPlannedStartDate(req.plannedStartDate());
    if (req.plannedFinishDate() != null) a.setPlannedFinishDate(req.plannedFinishDate());

    ResourceAssignment saved = assignmentRepo.save(a);
    return toResponse(saved, role, typeCode, variantId);
  }

  public void deleteRoleAssignment(UUID assignmentId) {
    ResourceAssignment a =
        assignmentRepo
            .findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("ResourceAssignment", assignmentId));
    assertActivityEditable(a.getActivityId());
    assignmentRepo.delete(a);
  }

  @Transactional(readOnly = true)
  public List<RoleAssignmentResponse> listForActivity(UUID activityId) {
    return assignmentRepo.findByActivityId(activityId).stream()
        .map(this::hydrate)
        .toList();
  }

  // ===== Helpers =====

  // Honor request.duration if present (>0). Else fall back to activity.originalDuration.
  // Final fallback is 1.0 so we never silently zero-out plannedCost when the activity
  // has no duration yet (e.g. brand-new draft).
  private static BigDecimal resolveDuration(BigDecimal requested, Activity activity) {
    if (requested != null && requested.signum() > 0) {
      return requested;
    }
    if (activity != null && activity.getOriginalDuration() != null
        && activity.getOriginalDuration() > 0) {
      return BigDecimal.valueOf(activity.getOriginalDuration());
    }
    return BigDecimal.ONE;
  }

  private UUID pickVariantId(String typeCode, RoleAssignmentRequest req) {
    return switch (typeCode) {
      case "LABOR", "MANPOWER" -> req.manpowerRoleRateId();
      case "EQUIPMENT" -> req.equipmentRoleVariantId();
      case "MATERIAL" -> req.materialRoleVariantId();
      default -> null;
    };
  }

  private RoleAssignmentResponse hydrate(ResourceAssignment a) {
    if (a.getRoleId() == null) {
      // Legacy resource-only row — surface as best-effort.
      return new RoleAssignmentResponse(
          a.getId(),
          a.getActivityId(),
          activityRepo.findById(a.getActivityId()).map(Activity::getName).orElse(null),
          a.getProjectId(),
          null,
          null,
          null,
          null,
          "(legacy resource-based row)",
          a.getHeadcount(),
          a.getDuration(),
          a.getQuantity(),
          toBig(a.getPlannedUnits()),
          toBig(a.getActualUnits()),
          toBig(a.getRemainingUnits()),
          a.getPlannedCost(),
          a.getActualCost(),
          a.getRemainingCost(),
          a.getEffectiveRate(),
          a.getUnit(),
          a.getRateType(),
          a.getPlannedStartDate(),
          a.getPlannedFinishDate(),
          isUnplanned(a));
    }
    ResourceRole role = roleRepo.findById(a.getRoleId()).orElse(null);
    String typeCode = role == null ? null : role.getResourceType().getCode().toUpperCase();
    UUID variantId = null;
    if (typeCode != null) {
      variantId =
          switch (typeCode) {
            case "LABOR", "MANPOWER" -> a.getManpowerRoleRateId();
            case "EQUIPMENT" -> a.getEquipmentRoleVariantId();
            case "MATERIAL" -> a.getMaterialRoleVariantId();
            default -> null;
          };
    }
    return toResponse(a, role, typeCode, variantId);
  }

  private RoleAssignmentResponse toResponse(
      ResourceAssignment a, ResourceRole role, String typeCode, UUID variantId) {
    String variantLabel = buildVariantLabel(typeCode, variantId);
    return new RoleAssignmentResponse(
        a.getId(),
        a.getActivityId(),
        activityRepo.findById(a.getActivityId()).map(Activity::getName).orElse(null),
        a.getProjectId(),
        a.getRoleId(),
        role == null ? null : role.getName(),
        typeCode,
        variantId,
        variantLabel,
        a.getHeadcount(),
        a.getDuration(),
        a.getQuantity(),
        toBig(a.getPlannedUnits()),
        toBig(a.getActualUnits()),
        toBig(a.getRemainingUnits()),
        a.getPlannedCost(),
        a.getActualCost(),
        a.getRemainingCost(),
        a.getEffectiveRate(),
        a.getUnit(),
        a.getRateType(),
        a.getPlannedStartDate(),
        a.getPlannedFinishDate(),
        isUnplanned(a));
  }

  // A row is "unplanned" when it has no demand booked against it — i.e. it was created
  // by the DPR write path (ensureAssignmentsExist) for a (role, variant) the planner
  // never added. plannedUnits and budgetedUnits are both zero/null in that case.
  private static boolean isUnplanned(ResourceAssignment a) {
    Double planned = a.getPlannedUnits();
    Double budgeted = a.getBudgetedUnits();
    return (planned == null || planned == 0.0) && (budgeted == null || budgeted == 0.0);
  }

  private String buildVariantLabel(String typeCode, UUID variantId) {
    if (typeCode == null || variantId == null) return null;
    return switch (typeCode) {
      case "LABOR", "MANPOWER" ->
          manpowerRepo
              .findById(variantId)
              .map(
                  r ->
                      categoryRepo.findById(r.getCategoryId()).map(c -> c.getName()).orElse("?")
                          + " / "
                          + gradeRepo.findById(r.getGradeId()).map(g -> g.getName()).orElse("?")
                          + " — "
                          + r.getUnit()
                          + " @ "
                          + r.getRate())
              .orElse(null);
      case "EQUIPMENT" ->
          equipmentRepo
              .findById(variantId)
              .map(
                  v ->
                      v.getMake()
                          + " / "
                          + v.getModel()
                          + " — "
                          + v.getUnit()
                          + " @ "
                          + v.getRate())
              .orElse(null);
      case "MATERIAL" ->
          materialRepo
              .findById(variantId)
              .map(v -> v.getSpecGrade() + " — " + v.getUnit() + " @ " + v.getRate())
              .orElse(null);
      default -> null;
    };
  }

  private static BigDecimal toBig(Double d) {
    return d == null ? null : BigDecimal.valueOf(d);
  }

  private void assertActivityEditable(UUID activityId) {
    if (activityId == null) return;
    Activity activity = activityRepo.findById(activityId).orElse(null);
    if (activity == null) return;
    if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
      throw new BusinessRuleException(
          "ACTIVITY_LOCKED",
          "Activity '" + activity.getCode() + "' is locked. Unlock it before editing.");
    }
  }

  private Optional<ResourceAssignment> findExisting(
      UUID activityId, UUID roleId, String typeCode, UUID variantId) {
    return switch (typeCode) {
      case "LABOR", "MANPOWER" ->
          assignmentRepo.findFirstByActivityIdAndRoleIdAndManpowerRoleRateId(
              activityId, roleId, variantId);
      case "EQUIPMENT" ->
          assignmentRepo.findFirstByActivityIdAndRoleIdAndEquipmentRoleVariantId(
              activityId, roleId, variantId);
      case "MATERIAL" ->
          assignmentRepo.findFirstByActivityIdAndRoleIdAndMaterialRoleVariantId(
              activityId, roleId, variantId);
      default -> Optional.empty();
    };
  }
}
