package com.bipros.resource.application.service.role;

import com.bipros.resource.application.dto.role.EquipmentRoleVariantRequest;
import com.bipros.resource.application.dto.role.EquipmentRoleVariantResponse;
import com.bipros.resource.application.dto.role.ManpowerRoleRateRequest;
import com.bipros.resource.application.dto.role.ManpowerRoleRateResponse;
import com.bipros.resource.application.dto.role.MaterialRoleVariantRequest;
import com.bipros.resource.application.dto.role.MaterialRoleVariantResponse;
import com.bipros.resource.application.dto.role.ProjectRoleRateOverrideRequest;
import com.bipros.resource.application.dto.role.ProjectRoleRateOverrideResponse;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.model.role.ProjectEquipmentRoleVariantOverride;
import com.bipros.resource.domain.model.role.ProjectManpowerRoleRateOverride;
import com.bipros.resource.domain.model.role.ProjectMaterialRoleVariantOverride;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.application.dto.ResourceRoleResponse;
import com.bipros.resource.application.dto.role.RoleWithVariantsRequest;
import com.bipros.resource.application.dto.role.RoleWithVariantsResponse;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ProjectEquipmentRoleVariantOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectManpowerRoleRateOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectMaterialRoleVariantOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RoleRateService {

  private final ResourceRoleRepository roleRepo;
  private final ManpowerRoleRateRepository manpowerRepo;
  private final EquipmentRoleVariantRepository equipmentRepo;
  private final MaterialRoleVariantRepository materialRepo;
  private final ManpowerCategoryMasterRepository categoryRepo;
  private final GradeMasterRepository gradeRepo;
  private final ProjectManpowerRoleRateOverrideRepository manpowerOverrideRepo;
  private final ProjectEquipmentRoleVariantOverrideRepository equipmentOverrideRepo;
  private final ProjectMaterialRoleVariantOverrideRepository materialOverrideRepo;
  private final ResourceAssignmentRepository assignmentRepo;
  private final ResourceTypeRepository resourceTypeRepo;

  // ===== Manpower =====

  @Transactional(readOnly = true)
  public List<ManpowerRoleRateResponse> listManpowerForRole(UUID roleId) {
    ResourceRole role = requireRole(roleId, "LABOR", "MANPOWER");
    return manpowerRepo.findByRoleIdAndActiveTrue(roleId).stream()
        .map(r -> toManpowerResponse(r, role))
        .toList();
  }

  public ManpowerRoleRateResponse createManpowerRate(UUID roleId, ManpowerRoleRateRequest req) {
    ResourceRole role = requireRole(roleId, "LABOR", "MANPOWER");
    manpowerRepo
        .findByRoleIdAndCategoryIdAndGradeId(roleId, req.categoryId(), req.gradeId())
        .ifPresent(
            existing -> {
              throw new IllegalStateException(
                  "Manpower rate already exists for (role,skillLevel,grade)");
            });
    ManpowerRoleRate saved =
        manpowerRepo.save(
            ManpowerRoleRate.builder()
                .roleId(roleId)
                .categoryId(req.categoryId())
                .gradeId(req.gradeId())
                .unit(req.unit())
                .rate(req.rate())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build());
    return toManpowerResponse(saved, role);
  }

  public ManpowerRoleRateResponse updateManpowerRate(UUID id, ManpowerRoleRateRequest req) {
    ManpowerRoleRate r =
        manpowerRepo
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Rate not found: " + id));
    r.setCategoryId(req.categoryId());
    r.setGradeId(req.gradeId());
    r.setUnit(req.unit());
    r.setRate(req.rate());
    if (req.active() != null) r.setActive(req.active());
    ManpowerRoleRate saved = manpowerRepo.save(r);
    ResourceRole role =
        roleRepo
            .findById(saved.getRoleId())
            .orElseThrow(() -> new IllegalStateException("Role missing"));
    return toManpowerResponse(saved, role);
  }

  public void deleteManpowerRate(UUID id) {
    long used = assignmentRepo.countByManpowerRoleRateId(id);
    if (used > 0) {
      throw new BusinessRuleException(
          "VARIANT_IN_USE",
          "This manpower variant is used on " + used
              + " activity assignment(s) and cannot be deleted. Remove it from those activities first.");
    }
    manpowerRepo.deleteById(id);
  }

  // ===== Equipment =====

  @Transactional(readOnly = true)
  public List<EquipmentRoleVariantResponse> listEquipmentForRole(UUID roleId) {
    ResourceRole role = requireRole(roleId, "EQUIPMENT");
    return equipmentRepo.findByRoleIdAndActiveTrue(roleId).stream()
        .map(v -> toEquipmentResponse(v, role))
        .toList();
  }

  public EquipmentRoleVariantResponse createEquipmentVariant(
      UUID roleId, EquipmentRoleVariantRequest req) {
    ResourceRole role = requireRole(roleId, "EQUIPMENT");
    equipmentRepo
        .findByRoleIdAndMakeAndModel(roleId, req.make(), req.model())
        .ifPresent(
            v -> {
              throw new IllegalStateException(
                  "Equipment variant already exists for (role,make,model)");
            });
    EquipmentRoleVariant saved =
        equipmentRepo.save(
            EquipmentRoleVariant.builder()
                .roleId(roleId)
                .make(req.make())
                .model(req.model())
                .unit(req.unit())
                .rate(req.rate())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build());
    return toEquipmentResponse(saved, role);
  }

  public EquipmentRoleVariantResponse updateEquipmentVariant(
      UUID id, EquipmentRoleVariantRequest req) {
    EquipmentRoleVariant v =
        equipmentRepo
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + id));
    v.setMake(req.make());
    v.setModel(req.model());
    v.setUnit(req.unit());
    v.setRate(req.rate());
    if (req.active() != null) v.setActive(req.active());
    EquipmentRoleVariant saved = equipmentRepo.save(v);
    ResourceRole role =
        roleRepo
            .findById(saved.getRoleId())
            .orElseThrow(() -> new IllegalStateException("Role missing"));
    return toEquipmentResponse(saved, role);
  }

  public void deleteEquipmentVariant(UUID id) {
    long used = assignmentRepo.countByEquipmentRoleVariantId(id);
    if (used > 0) {
      throw new BusinessRuleException(
          "VARIANT_IN_USE",
          "This equipment variant is used on " + used
              + " activity assignment(s) and cannot be deleted. Remove it from those activities first.");
    }
    equipmentRepo.deleteById(id);
  }

  // ===== Material =====

  @Transactional(readOnly = true)
  public List<MaterialRoleVariantResponse> listMaterialForRole(UUID roleId) {
    ResourceRole role = requireRole(roleId, "MATERIAL");
    return materialRepo.findByRoleIdAndActiveTrue(roleId).stream()
        .map(v -> toMaterialResponse(v, role))
        .toList();
  }

  public MaterialRoleVariantResponse createMaterialVariant(
      UUID roleId, MaterialRoleVariantRequest req) {
    ResourceRole role = requireRole(roleId, "MATERIAL");
    materialRepo
        .findByRoleIdAndSpecGrade(roleId, req.specGrade())
        .ifPresent(
            v -> {
              throw new IllegalStateException(
                  "Material variant already exists for (role,specGrade)");
            });
    MaterialRoleVariant saved =
        materialRepo.save(
            MaterialRoleVariant.builder()
                .roleId(roleId)
                .specGrade(req.specGrade())
                .unit(req.unit())
                .rate(req.rate())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build());
    return toMaterialResponse(saved, role);
  }

  public MaterialRoleVariantResponse updateMaterialVariant(
      UUID id, MaterialRoleVariantRequest req) {
    MaterialRoleVariant v =
        materialRepo
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + id));
    v.setSpecGrade(req.specGrade());
    v.setUnit(req.unit());
    v.setRate(req.rate());
    if (req.active() != null) v.setActive(req.active());
    MaterialRoleVariant saved = materialRepo.save(v);
    ResourceRole role =
        roleRepo
            .findById(saved.getRoleId())
            .orElseThrow(() -> new IllegalStateException("Role missing"));
    return toMaterialResponse(saved, role);
  }

  public void deleteMaterialVariant(UUID id) {
    long used = assignmentRepo.countByMaterialRoleVariantId(id);
    if (used > 0) {
      throw new BusinessRuleException(
          "VARIANT_IN_USE",
          "This material variant is used on " + used
              + " activity assignment(s) and cannot be deleted. Remove it from those activities first.");
    }
    materialRepo.deleteById(id);
  }

  // ===== Project Overrides =====

  @Transactional(readOnly = true)
  public List<ProjectRoleRateOverrideResponse> listOverridesForProject(UUID projectId) {
    List<ProjectRoleRateOverrideResponse> result = new java.util.ArrayList<>();
    manpowerOverrideRepo
        .findByProjectIdAndActiveTrue(projectId)
        .forEach(
            o -> {
              ManpowerRoleRate r = manpowerRepo.findById(o.getManpowerRoleRateId()).orElse(null);
              result.add(toOverrideResponse(o, r));
            });
    equipmentOverrideRepo
        .findByProjectIdAndActiveTrue(projectId)
        .forEach(
            o -> {
              EquipmentRoleVariant r =
                  equipmentRepo.findById(o.getEquipmentRoleVariantId()).orElse(null);
              result.add(toOverrideResponse(o, r));
            });
    materialOverrideRepo
        .findByProjectIdAndActiveTrue(projectId)
        .forEach(
            o -> {
              MaterialRoleVariant r =
                  materialRepo.findById(o.getMaterialRoleVariantId()).orElse(null);
              result.add(toOverrideResponse(o, r));
            });
    return result;
  }

  public ProjectRoleRateOverrideResponse createOverride(
      UUID projectId, ProjectRoleRateOverrideRequest req) {
    long set =
        java.util.stream.Stream.of(
                req.manpowerRoleRateId(), req.equipmentRoleVariantId(), req.materialRoleVariantId())
            .filter(java.util.Objects::nonNull)
            .count();
    if (set != 1) {
      throw new IllegalArgumentException(
          "Exactly one of manpowerRoleRateId / equipmentRoleVariantId / materialRoleVariantId must be set");
    }
    boolean active = req.active() == null ? Boolean.TRUE : req.active();
    if (req.manpowerRoleRateId() != null) {
      ProjectManpowerRoleRateOverride saved =
          manpowerOverrideRepo.save(
              ProjectManpowerRoleRateOverride.builder()
                  .projectId(projectId)
                  .manpowerRoleRateId(req.manpowerRoleRateId())
                  .overrideRate(req.overrideRate())
                  .active(active)
                  .build());
      return toOverrideResponse(saved, manpowerRepo.findById(req.manpowerRoleRateId()).orElse(null));
    }
    if (req.equipmentRoleVariantId() != null) {
      ProjectEquipmentRoleVariantOverride saved =
          equipmentOverrideRepo.save(
              ProjectEquipmentRoleVariantOverride.builder()
                  .projectId(projectId)
                  .equipmentRoleVariantId(req.equipmentRoleVariantId())
                  .overrideRate(req.overrideRate())
                  .active(active)
                  .build());
      return toOverrideResponse(
          saved, equipmentRepo.findById(req.equipmentRoleVariantId()).orElse(null));
    }
    ProjectMaterialRoleVariantOverride saved =
        materialOverrideRepo.save(
            ProjectMaterialRoleVariantOverride.builder()
                .projectId(projectId)
                .materialRoleVariantId(req.materialRoleVariantId())
                .overrideRate(req.overrideRate())
                .active(active)
                .build());
    return toOverrideResponse(
        saved, materialRepo.findById(req.materialRoleVariantId()).orElse(null));
  }

  public void deleteManpowerOverride(UUID id) {
    manpowerOverrideRepo.deleteById(id);
  }

  public void deleteEquipmentOverride(UUID id) {
    equipmentOverrideRepo.deleteById(id);
  }

  public void deleteMaterialOverride(UUID id) {
    materialOverrideRepo.deleteById(id);
  }

  // ===== helpers =====

  private ResourceRole requireRole(UUID roleId, String... allowedTypeCodes) {
    ResourceRole role =
        roleRepo
            .findById(roleId)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
    String typeCode = role.getResourceType().getCode().toUpperCase();
    for (String allowed : allowedTypeCodes) {
      if (allowed.equalsIgnoreCase(typeCode)) return role;
    }
    throw new IllegalStateException(
        "Role " + role.getName() + " is type " + typeCode + ", expected one of "
            + java.util.Arrays.toString(allowedTypeCodes));
  }

  private ManpowerRoleRateResponse toManpowerResponse(ManpowerRoleRate r, ResourceRole role) {
    ManpowerCategoryMaster cat = categoryRepo.findById(r.getCategoryId()).orElse(null);
    GradeMaster g = gradeRepo.findById(r.getGradeId()).orElse(null);
    return new ManpowerRoleRateResponse(
        r.getId(),
        r.getRoleId(),
        role == null ? null : role.getName(),
        r.getCategoryId(),
        cat == null ? null : cat.getName(),
        r.getGradeId(),
        g == null ? null : g.getName(),
        r.getUnit(),
        r.getRate(),
        r.getActive());
  }

  private EquipmentRoleVariantResponse toEquipmentResponse(
      EquipmentRoleVariant v, ResourceRole role) {
    return new EquipmentRoleVariantResponse(
        v.getId(),
        v.getRoleId(),
        role == null ? null : role.getName(),
        v.getMake(),
        v.getModel(),
        v.getUnit(),
        v.getRate(),
        v.getActive());
  }

  private MaterialRoleVariantResponse toMaterialResponse(
      MaterialRoleVariant v, ResourceRole role) {
    return new MaterialRoleVariantResponse(
        v.getId(),
        v.getRoleId(),
        role == null ? null : role.getName(),
        v.getSpecGrade(),
        v.getUnit(),
        v.getRate(),
        v.getActive());
  }

  private ProjectRoleRateOverrideResponse toOverrideResponse(
      ProjectManpowerRoleRateOverride o, ManpowerRoleRate r) {
    String label =
        r == null
            ? "(missing)"
            : "Manpower variant " + r.getId();
    return new ProjectRoleRateOverrideResponse(
        o.getId(),
        o.getProjectId(),
        "MANPOWER",
        o.getManpowerRoleRateId(),
        label,
        o.getOverrideRate(),
        r == null ? null : r.getRate(),
        o.getActive());
  }

  private ProjectRoleRateOverrideResponse toOverrideResponse(
      ProjectEquipmentRoleVariantOverride o, EquipmentRoleVariant r) {
    String label =
        r == null ? "(missing)" : r.getMake() + " / " + r.getModel();
    return new ProjectRoleRateOverrideResponse(
        o.getId(),
        o.getProjectId(),
        "EQUIPMENT",
        o.getEquipmentRoleVariantId(),
        label,
        o.getOverrideRate(),
        r == null ? null : r.getRate(),
        o.getActive());
  }

  private ProjectRoleRateOverrideResponse toOverrideResponse(
      ProjectMaterialRoleVariantOverride o, MaterialRoleVariant r) {
    String label = r == null ? "(missing)" : r.getSpecGrade();
    return new ProjectRoleRateOverrideResponse(
        o.getId(),
        o.getProjectId(),
        "MATERIAL",
        o.getMaterialRoleVariantId(),
        label,
        o.getOverrideRate(),
        r == null ? null : r.getRate(),
        o.getActive());
  }

  // ===========================================================================================
  // Combined role + variants save — one transactional call. Replace-by-id semantics: variants
  // with `id` are updated, without `id` are inserted, missing-from-payload are deleted (subject
  // to the in-use guard). Atomicity matters: if any variant fails to delete due to VARIANT_IN_USE,
  // the whole transaction rolls back and the role stays in its prior state.
  // ===========================================================================================

  public RoleWithVariantsResponse createRoleWithVariants(RoleWithVariantsRequest req) {
    ResourceType type =
        resourceTypeRepo
            .findById(req.resourceTypeId())
            .orElseThrow(
                () -> new BusinessRuleException("RESOURCE_TYPE_NOT_FOUND",
                    "Resource type not found: " + req.resourceTypeId()));
    ResourceRole role =
        ResourceRole.builder()
            .code(req.code().trim())
            .name(req.name().trim())
            .description(req.description())
            .resourceType(type)
            .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
            .active(req.active() == null ? Boolean.TRUE : req.active())
            .build();
    ResourceRole saved = roleRepo.save(role);
    replaceVariants(saved, type, req);
    return buildWithVariantsResponse(saved);
  }

  public RoleWithVariantsResponse updateRoleWithVariants(UUID roleId, RoleWithVariantsRequest req) {
    ResourceRole role =
        roleRepo
            .findById(roleId)
            .orElseThrow(
                () -> new BusinessRuleException("ROLE_NOT_FOUND", "Role not found: " + roleId));
    ResourceType type =
        resourceTypeRepo
            .findById(req.resourceTypeId())
            .orElseThrow(
                () -> new BusinessRuleException("RESOURCE_TYPE_NOT_FOUND",
                    "Resource type not found: " + req.resourceTypeId()));
    role.setCode(req.code().trim());
    role.setName(req.name().trim());
    role.setDescription(req.description());
    role.setResourceType(type);
    if (req.sortOrder() != null) role.setSortOrder(req.sortOrder());
    if (req.active() != null) role.setActive(req.active());
    ResourceRole saved = roleRepo.save(role);
    replaceVariants(saved, type, req);
    return buildWithVariantsResponse(saved);
  }

  @Transactional(readOnly = true)
  public RoleWithVariantsResponse getRoleWithVariants(UUID roleId) {
    ResourceRole role =
        roleRepo
            .findById(roleId)
            .orElseThrow(
                () -> new BusinessRuleException("ROLE_NOT_FOUND", "Role not found: " + roleId));
    return buildWithVariantsResponse(role);
  }

  private void replaceVariants(ResourceRole role, ResourceType type, RoleWithVariantsRequest req) {
    String typeCode = type.getCode().toUpperCase();
    UUID roleId = role.getId();

    if ("LABOR".equals(typeCode) || "MANPOWER".equals(typeCode)) {
      List<ManpowerRoleRate> existing = manpowerRepo.findByRoleIdAndActiveTrue(roleId);
      java.util.Set<UUID> incomingIds =
          req.manpowerVariants() == null
              ? java.util.Set.of()
              : req.manpowerVariants().stream()
                  .map(RoleWithVariantsRequest.ManpowerVariantInput::id)
                  .filter(java.util.Objects::nonNull)
                  .collect(java.util.stream.Collectors.toSet());
      for (ManpowerRoleRate e : existing) {
        if (!incomingIds.contains(e.getId())) {
          long used = assignmentRepo.countByManpowerRoleRateId(e.getId());
          if (used > 0) {
            throw new BusinessRuleException(
                "VARIANT_IN_USE",
                "Cannot remove manpower variant " + e.getId() + " — used on " + used
                    + " activity assignment(s).");
          }
          manpowerRepo.deleteById(e.getId());
        }
      }
      if (req.manpowerVariants() != null) {
        for (RoleWithVariantsRequest.ManpowerVariantInput in : req.manpowerVariants()) {
          if (in.id() == null) {
            manpowerRepo.save(
                ManpowerRoleRate.builder()
                    .roleId(roleId)
                    .categoryId(in.categoryId())
                    .gradeId(in.gradeId())
                    .unit(in.unit())
                    .rate(in.rate())
                    .active(in.active() == null ? Boolean.TRUE : in.active())
                    .build());
          } else {
            ManpowerRoleRate v =
                manpowerRepo
                    .findById(in.id())
                    .orElseThrow(
                        () ->
                            new BusinessRuleException(
                                "VARIANT_NOT_FOUND", "Manpower variant not found: " + in.id()));
            v.setCategoryId(in.categoryId());
            v.setGradeId(in.gradeId());
            v.setUnit(in.unit());
            v.setRate(in.rate());
            if (in.active() != null) v.setActive(in.active());
            manpowerRepo.save(v);
          }
        }
      }
    } else if ("EQUIPMENT".equals(typeCode)) {
      List<EquipmentRoleVariant> existing = equipmentRepo.findByRoleIdAndActiveTrue(roleId);
      java.util.Set<UUID> incomingIds =
          req.equipmentVariants() == null
              ? java.util.Set.of()
              : req.equipmentVariants().stream()
                  .map(RoleWithVariantsRequest.EquipmentVariantInput::id)
                  .filter(java.util.Objects::nonNull)
                  .collect(java.util.stream.Collectors.toSet());
      for (EquipmentRoleVariant e : existing) {
        if (!incomingIds.contains(e.getId())) {
          long used = assignmentRepo.countByEquipmentRoleVariantId(e.getId());
          if (used > 0) {
            throw new BusinessRuleException(
                "VARIANT_IN_USE",
                "Cannot remove equipment variant " + e.getId() + " — used on " + used
                    + " activity assignment(s).");
          }
          equipmentRepo.deleteById(e.getId());
        }
      }
      if (req.equipmentVariants() != null) {
        for (RoleWithVariantsRequest.EquipmentVariantInput in : req.equipmentVariants()) {
          if (in.id() == null) {
            equipmentRepo.save(
                EquipmentRoleVariant.builder()
                    .roleId(roleId)
                    .make(in.make())
                    .model(in.model())
                    .unit(in.unit())
                    .rate(in.rate())
                    .active(in.active() == null ? Boolean.TRUE : in.active())
                    .build());
          } else {
            EquipmentRoleVariant v =
                equipmentRepo
                    .findById(in.id())
                    .orElseThrow(
                        () ->
                            new BusinessRuleException(
                                "VARIANT_NOT_FOUND", "Equipment variant not found: " + in.id()));
            v.setMake(in.make());
            v.setModel(in.model());
            v.setUnit(in.unit());
            v.setRate(in.rate());
            if (in.active() != null) v.setActive(in.active());
            equipmentRepo.save(v);
          }
        }
      }
    } else if ("MATERIAL".equals(typeCode)) {
      List<MaterialRoleVariant> existing = materialRepo.findByRoleIdAndActiveTrue(roleId);
      java.util.Set<UUID> incomingIds =
          req.materialVariants() == null
              ? java.util.Set.of()
              : req.materialVariants().stream()
                  .map(RoleWithVariantsRequest.MaterialVariantInput::id)
                  .filter(java.util.Objects::nonNull)
                  .collect(java.util.stream.Collectors.toSet());
      for (MaterialRoleVariant e : existing) {
        if (!incomingIds.contains(e.getId())) {
          long used = assignmentRepo.countByMaterialRoleVariantId(e.getId());
          if (used > 0) {
            throw new BusinessRuleException(
                "VARIANT_IN_USE",
                "Cannot remove material variant " + e.getId() + " — used on " + used
                    + " activity assignment(s).");
          }
          materialRepo.deleteById(e.getId());
        }
      }
      if (req.materialVariants() != null) {
        for (RoleWithVariantsRequest.MaterialVariantInput in : req.materialVariants()) {
          if (in.id() == null) {
            materialRepo.save(
                MaterialRoleVariant.builder()
                    .roleId(roleId)
                    .specGrade(in.specGrade())
                    .unit(in.unit())
                    .rate(in.rate())
                    .active(in.active() == null ? Boolean.TRUE : in.active())
                    .build());
          } else {
            MaterialRoleVariant v =
                materialRepo
                    .findById(in.id())
                    .orElseThrow(
                        () ->
                            new BusinessRuleException(
                                "VARIANT_NOT_FOUND", "Material variant not found: " + in.id()));
            v.setSpecGrade(in.specGrade());
            v.setUnit(in.unit());
            v.setRate(in.rate());
            if (in.active() != null) v.setActive(in.active());
            materialRepo.save(v);
          }
        }
      }
    }
  }

  private RoleWithVariantsResponse buildWithVariantsResponse(ResourceRole role) {
    String typeCode = role.getResourceType().getCode().toUpperCase();
    List<ManpowerRoleRateResponse> manpower = List.of();
    List<EquipmentRoleVariantResponse> equipment = List.of();
    List<MaterialRoleVariantResponse> material = List.of();
    if ("LABOR".equals(typeCode) || "MANPOWER".equals(typeCode)) {
      manpower =
          manpowerRepo.findByRoleIdAndActiveTrue(role.getId()).stream()
              .map(r -> toManpowerResponse(r, role))
              .toList();
    } else if ("EQUIPMENT".equals(typeCode)) {
      equipment =
          equipmentRepo.findByRoleIdAndActiveTrue(role.getId()).stream()
              .map(v -> toEquipmentResponse(v, role))
              .toList();
    } else if ("MATERIAL".equals(typeCode)) {
      material =
          materialRepo.findByRoleIdAndActiveTrue(role.getId()).stream()
              .map(v -> toMaterialResponse(v, role))
              .toList();
    }
    return new RoleWithVariantsResponse(
        ResourceRoleResponse.from(role), manpower, equipment, material);
  }
}
