package com.bipros.resource.application.service.role;

import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ProjectEquipmentRoleVariantOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectManpowerRoleRateOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectMaterialRoleVariantOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the effective rate for a (project, role-type, variant) combination via the
 * two-tier chain: per-project override → variant's default rate → null.
 *
 * <p>This replaces the legacy two-tier chain on {@code ResourceAssignmentCostRollupListener}
 * (which walked {@code ProjectResource.rateOverride → Resource.costPerUnit}) in the new
 * role-only model where no Resource/ProjectResource instance exists.
 */
@Service
@RequiredArgsConstructor
public class RoleRateResolver {

  private final ManpowerRoleRateRepository manpowerRepo;
  private final EquipmentRoleVariantRepository equipmentRepo;
  private final MaterialRoleVariantRepository materialRepo;
  private final ProjectManpowerRoleRateOverrideRepository manpowerOverrideRepo;
  private final ProjectEquipmentRoleVariantOverrideRepository equipmentOverrideRepo;
  private final ProjectMaterialRoleVariantOverrideRepository materialOverrideRepo;

  /**
   * Resolve the effective rate for a variant. Returns {@code null} when the variant does
   * not exist (callers should surface this as a warning, not a failure).
   *
   * @param projectId may be {@code null} — when null, skips the override tier
   * @param resourceTypeCode one of {@code LABOR}/{@code MANPOWER}/{@code EQUIPMENT}/{@code MATERIAL}
   * @param variantId the manpower-rate / equipment-variant / material-variant ID
   */
  public BigDecimal resolveRate(UUID projectId, String resourceTypeCode, UUID variantId) {
    if (variantId == null || resourceTypeCode == null) {
      return null;
    }
    String code = resourceTypeCode.toUpperCase();
    return switch (code) {
      case "LABOR", "MANPOWER" -> resolveManpower(projectId, variantId);
      case "EQUIPMENT" -> resolveEquipment(projectId, variantId);
      case "MATERIAL" -> resolveMaterial(projectId, variantId);
      default -> null;
    };
  }

  /** Resolve the unit (DAY / HOUR / MT / BAG / ...) for a variant. */
  public String resolveUnit(String resourceTypeCode, UUID variantId) {
    if (variantId == null || resourceTypeCode == null) return null;
    String code = resourceTypeCode.toUpperCase();
    return switch (code) {
      case "LABOR", "MANPOWER" ->
          manpowerRepo.findById(variantId).map(ManpowerRoleRate::getUnit).orElse(null);
      case "EQUIPMENT" ->
          equipmentRepo.findById(variantId).map(EquipmentRoleVariant::getUnit).orElse(null);
      case "MATERIAL" ->
          materialRepo.findById(variantId).map(MaterialRoleVariant::getUnit).orElse(null);
      default -> null;
    };
  }

  /** Returns true iff a project-level override is present and active for this variant. */
  public boolean hasOverride(UUID projectId, String resourceTypeCode, UUID variantId) {
    if (projectId == null || variantId == null || resourceTypeCode == null) return false;
    String code = resourceTypeCode.toUpperCase();
    return switch (code) {
      case "LABOR", "MANPOWER" ->
          manpowerOverrideRepo
              .findByProjectIdAndManpowerRoleRateIdAndActiveTrue(projectId, variantId)
              .isPresent();
      case "EQUIPMENT" ->
          equipmentOverrideRepo
              .findByProjectIdAndEquipmentRoleVariantIdAndActiveTrue(projectId, variantId)
              .isPresent();
      case "MATERIAL" ->
          materialOverrideRepo
              .findByProjectIdAndMaterialRoleVariantIdAndActiveTrue(projectId, variantId)
              .isPresent();
      default -> false;
    };
  }

  private BigDecimal resolveManpower(UUID projectId, UUID variantId) {
    if (projectId != null) {
      Optional<BigDecimal> override =
          manpowerOverrideRepo
              .findByProjectIdAndManpowerRoleRateIdAndActiveTrue(projectId, variantId)
              .map(o -> o.getOverrideRate());
      if (override.isPresent()) return override.get();
    }
    return manpowerRepo.findById(variantId).map(ManpowerRoleRate::getRate).orElse(null);
  }

  private BigDecimal resolveEquipment(UUID projectId, UUID variantId) {
    if (projectId != null) {
      Optional<BigDecimal> override =
          equipmentOverrideRepo
              .findByProjectIdAndEquipmentRoleVariantIdAndActiveTrue(projectId, variantId)
              .map(o -> o.getOverrideRate());
      if (override.isPresent()) return override.get();
    }
    return equipmentRepo.findById(variantId).map(EquipmentRoleVariant::getRate).orElse(null);
  }

  private BigDecimal resolveMaterial(UUID projectId, UUID variantId) {
    if (projectId != null) {
      Optional<BigDecimal> override =
          materialOverrideRepo
              .findByProjectIdAndMaterialRoleVariantIdAndActiveTrue(projectId, variantId)
              .map(o -> o.getOverrideRate());
      if (override.isPresent()) return override.get();
    }
    return materialRepo.findById(variantId).map(MaterialRoleVariant::getRate).orElse(null);
  }
}
