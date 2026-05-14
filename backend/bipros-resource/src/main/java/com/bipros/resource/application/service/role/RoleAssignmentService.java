package com.bipros.resource.application.service.role;

import com.bipros.activity.domain.model.Activity;
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
import java.util.UUID;

/**
 * Role-based activity demand service. New flow: activity demand is {@code role + variant +
 * headcount × duration} (manpower / equipment) or {@code role + variant + quantity} (material).
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

    // Role-only simplification: planning is just headcount (manpower/equipment) or
    // quantity (material). Duration was dropped to remove "which day each worker arrives"
    // complexity. plannedUnits = headcount (or quantity), regardless of variant's rate unit.
    BigDecimal plannedUnits;
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
      plannedUnits = BigDecimal.valueOf(headcount);
      quantity = null;
    }
    BigDecimal plannedCost = plannedUnits.multiply(rate);

    LocalDate plannedStart = req.plannedStartDate();
    LocalDate plannedFinish = req.plannedFinishDate();
    if (plannedStart == null || plannedFinish == null) {
      Activity activity = activityRepo.findById(req.activityId()).orElse(null);
      if (activity != null) {
        if (plannedStart == null) plannedStart = activity.getPlannedStartDate();
        if (plannedFinish == null) plannedFinish = activity.getPlannedFinishDate();
      }
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
            .duration(null)
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

    BigDecimal plannedUnits;
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
      plannedUnits = BigDecimal.valueOf(headcount);
      quantity = null;
    }
    BigDecimal plannedCost = plannedUnits.multiply(rate);

    a.setRoleId(req.roleId());
    a.setManpowerRoleRateId(
        "LABOR".equals(typeCode) || "MANPOWER".equals(typeCode) ? variantId : null);
    a.setEquipmentRoleVariantId("EQUIPMENT".equals(typeCode) ? variantId : null);
    a.setMaterialRoleVariantId("MATERIAL".equals(typeCode) ? variantId : null);
    a.setHeadcount(headcount);
    a.setDuration(null);
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
    if (!assignmentRepo.existsById(assignmentId)) {
      throw new ResourceNotFoundException("ResourceAssignment", assignmentId);
    }
    assignmentRepo.deleteById(assignmentId);
  }

  @Transactional(readOnly = true)
  public List<RoleAssignmentResponse> listForActivity(UUID activityId) {
    return assignmentRepo.findByActivityId(activityId).stream()
        .map(this::hydrate)
        .toList();
  }

  // ===== Helpers =====

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
          a.getPlannedFinishDate());
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
        a.getPlannedFinishDate());
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
}
