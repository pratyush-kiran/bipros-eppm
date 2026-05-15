package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.CreateProductivityNormRequest;
import com.bipros.resource.application.dto.ProductivityNormResponse;
import com.bipros.resource.application.dto.SuggestedUnitsResponse;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.bipros.resource.domain.service.ProductivityNormLookupService;
import com.bipros.resource.domain.service.ResolvedNorm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ProductivityNormService {

  private final ProductivityNormRepository repository;
  private final WorkActivityRepository workActivityRepository;
  private final ResourceTypeRepository resourceTypeRepository;
  private final ResourceRepository resourceRepository;
  private final ActivityRepository activityRepository;
  private final ProductivityNormLookupService normLookupService;
  private final AuditService auditService;

  /**
   * Suggest a {@code plannedUnits} value for an upcoming resource assignment based on the
   * applicable productivity norm. The activity must be linked to a {@code WorkActivity}
   * (via {@code Activity.workActivityId}) so a norm can be resolved; otherwise the response
   * carries a null suggestion and a NONE source.
   *
   * <p>Formula (when a norm with positive {@code outputPerDay} is found):
   * {@code suggestedPlannedUnits = quantity / outputPerDay} → days the resource is needed.
   *
   * <p>The endpoint deliberately requires the caller to pass {@code quantity} since
   * {@link Activity} doesn't store its target quantity (it lives on linked BoQ items, with
   * project-specific multiplicities). Callers that want auto-quantity should pre-fetch the
   * BoQ total and pass it here.
   */
  @Transactional(readOnly = true)
  public SuggestedUnitsResponse suggestUnits(UUID activityId, UUID resourceId, BigDecimal quantity) {
    Activity activity = activityRepository.findById(activityId)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));
    if (resourceId != null && !resourceRepository.existsById(resourceId)) {
      throw new ResourceNotFoundException("Resource", resourceId);
    }
    UUID workActivityId = activity.getWorkActivityId();
    if (workActivityId == null) {
      return new SuggestedUnitsResponse(activityId, resourceId, null, quantity, null, null, null,
          "Activity is not linked to a WorkActivity master row — no norm can be resolved.",
          ResolvedNorm.Source.NONE.name());
    }

    ResolvedNorm norm = normLookupService.resolve(workActivityId, resourceId);
    if (norm.outputPerDay() == null || norm.outputPerDay().signum() <= 0) {
      return new SuggestedUnitsResponse(activityId, resourceId, workActivityId, quantity, null, null, null,
          "No productivity norm with positive daily output found for this activity + resource.",
          norm.source().name());
    }
    if (quantity == null || quantity.signum() <= 0) {
      return new SuggestedUnitsResponse(activityId, resourceId, workActivityId, quantity,
          norm.unit(), norm.outputPerDay(), null,
          "Pass a positive quantity (in " + norm.unit() + ") to compute suggested planned units.",
          norm.source().name());
    }

    BigDecimal suggested = quantity.divide(norm.outputPerDay(), 2, RoundingMode.HALF_UP);
    String basis = String.format("%s %s ÷ %s/day = %s days",
        quantity.stripTrailingZeros().toPlainString(),
        norm.unit() == null ? "units" : norm.unit(),
        norm.outputPerDay().stripTrailingZeros().toPlainString(),
        suggested.stripTrailingZeros().toPlainString());
    return new SuggestedUnitsResponse(
        activityId, resourceId, workActivityId, quantity,
        norm.unit(), norm.outputPerDay(), suggested, basis, norm.source().name());
  }

  public ProductivityNormResponse create(CreateProductivityNormRequest request) {
    log.info("Creating productivity norm: type={}, workActivityId={}, resourceTypeId={}, resourceId={}",
        request.normType(), request.workActivityId(), request.resourceTypeId(), request.resourceId());
    ProductivityNorm norm = toEntity(new ProductivityNorm(), request);
    ProductivityNorm saved = repository.save(norm);
    auditService.logCreate("ProductivityNorm", saved.getId(), ProductivityNormResponse.from(saved));
    return ProductivityNormResponse.from(saved);
  }

  public List<ProductivityNormResponse> createBulk(List<CreateProductivityNormRequest> requests) {
    List<ProductivityNorm> entities = requests.stream()
        .map(r -> toEntity(new ProductivityNorm(), r))
        .toList();
    return repository.saveAll(entities).stream()
        .map(ProductivityNormResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ProductivityNormResponse get(UUID id) {
    return ProductivityNormResponse.from(find(id));
  }

  @Transactional(readOnly = true)
  public List<ProductivityNormResponse> list(ProductivityNormType normType) {
    List<ProductivityNorm> entities = normType == null
        ? repository.findAll()
        : repository.findByNormType(normType);
    return entities.stream().map(ProductivityNormResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public List<ProductivityNormResponse> listByWorkActivity(UUID workActivityId) {
    return repository.findByWorkActivityId(workActivityId).stream()
        .map(ProductivityNormResponse::from)
        .toList();
  }

  public ProductivityNormResponse update(UUID id, CreateProductivityNormRequest request) {
    ProductivityNorm norm = find(id);
    toEntity(norm, request);
    ProductivityNorm updated = repository.save(norm);
    auditService.logUpdate("ProductivityNorm", id, "norm", null, ProductivityNormResponse.from(updated));
    return ProductivityNormResponse.from(updated);
  }

  public void delete(UUID id) {
    if (!repository.existsById(id)) {
      throw new ResourceNotFoundException("ProductivityNorm", id);
    }
    repository.deleteById(id);
    auditService.logDelete("ProductivityNorm", id);
  }

  private ProductivityNorm find(UUID id) {
    return repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ProductivityNorm", id));
  }

  private ProductivityNorm toEntity(ProductivityNorm norm, CreateProductivityNormRequest r) {
    // Legacy scope guards stay for back-compat: a single row should not mix Type and Resource
    // (mutually exclusive default vs override). The role-keyed scope (roleId + variant) is a
    // separate axis — it can coexist with legacy fields on imported rows but new rows from the
    // admin form will use exactly one of {legacy Type, legacy Resource, role-keyed, unscoped}.
    if (r.resourceTypeId() != null && r.resourceId() != null) {
      throw new BusinessRuleException("NORM_SCOPE_AMBIGUOUS",
          "Provide either resourceTypeId (default scope) or resourceId (override) — not both");
    }

    WorkActivity workActivity = resolveWorkActivity(r);
    enforceScopeUniqueness(norm, workActivity.getId(), r);
    norm.setWorkActivity(workActivity);
    norm.setActivityName(workActivity.getName());

    norm.setResourceType(r.resourceTypeId() == null ? null
        : resourceTypeRepository.findById(r.resourceTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("ResourceType", r.resourceTypeId())));

    Resource resource = r.resourceId() == null ? null
        : resourceRepository.findById(r.resourceId())
            .orElseThrow(() -> new ResourceNotFoundException("Resource", r.resourceId()));
    norm.setResource(resource);

    norm.setRoleId(r.roleId());
    norm.setCategoryId(r.categoryId());
    norm.setGradeId(r.gradeId());
    norm.setMake(blankToNull(r.make()));
    norm.setModel(blankToNull(r.model()));

    norm.setNormType(r.normType());
    norm.setUnit(r.unit());
    norm.setOutputPerManPerDay(r.outputPerManPerDay());
    norm.setOutputPerHour(r.outputPerHour());
    norm.setCrewSize(r.crewSize());
    norm.setWorkingHoursPerDay(r.workingHoursPerDay());
    norm.setFuelLitresPerHour(r.fuelLitresPerHour());
    norm.setEquipmentSpec(r.equipmentSpec());
    norm.setRemarks(r.remarks());

    BigDecimal derived = r.outputPerDay();
    if (derived == null) {
      if (r.normType() == ProductivityNormType.MANPOWER
          && r.outputPerManPerDay() != null && r.crewSize() != null) {
        derived = r.outputPerManPerDay().multiply(BigDecimal.valueOf(r.crewSize()));
      } else if (r.normType() == ProductivityNormType.EQUIPMENT
          && r.outputPerHour() != null && r.workingHoursPerDay() != null) {
        derived = r.outputPerHour().multiply(BigDecimal.valueOf(r.workingHoursPerDay()));
      }
    }
    norm.setOutputPerDay(derived);
    return norm;
  }

  private void enforceScopeUniqueness(
      ProductivityNorm current, UUID workActivityId, CreateProductivityNormRequest r) {
    UUID resourceTypeId = r.resourceTypeId();
    UUID resourceId = r.resourceId();
    if (resourceId != null) {
      repository.findFirstByWorkActivityIdAndResourceId(workActivityId, resourceId)
          .filter(existing -> !existing.getId().equals(current.getId()))
          .ifPresent(existing -> {
            throw new BusinessRuleException("NORM_SCOPE_DUPLICATE_RESOURCE",
                "A productivity norm already exists for this activity + specific resource");
          });
    } else if (resourceTypeId != null) {
      repository.findFirstByWorkActivityIdAndResourceIsNullAndResourceTypeId(
              workActivityId, resourceTypeId)
          .filter(existing -> !existing.getId().equals(current.getId()))
          .ifPresent(existing -> {
            throw new BusinessRuleException("NORM_SCOPE_DUPLICATE_TYPE",
                "A productivity norm already exists for this activity + resource type");
          });
    } else if (r.roleId() != null) {
      // Variant scope (roleId + categoryId/gradeId for manpower, or make/model for equipment).
      repository
          .findFirstByWorkActivityIdAndRoleIdAndCategoryIdAndGradeIdAndMakeAndModelAndNormType(
              workActivityId,
              r.roleId(),
              r.categoryId(),
              r.gradeId(),
              blankToNull(r.make()),
              blankToNull(r.model()),
              r.normType())
          .filter(existing -> !existing.getId().equals(current.getId()))
          .ifPresent(existing -> {
            throw new BusinessRuleException("NORM_SCOPE_DUPLICATE_VARIANT",
                "A productivity norm already exists for this activity + role + variant");
          });
    } else {
      // Fully unscoped: no legacy scope, no role-keyed scope. Two unscoped rows for the same
      // (workActivity, normType) silently race because the resolver does ORDER BY created_at
      // LIMIT 1 and ignores the rest. If the admin needs to differentiate productivity
      // (e.g. a fast crew vs a regular crew), they should use Variant scope.
      repository
          .findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
              workActivityId, r.normType())
          .filter(existing -> !existing.getId().equals(current.getId()))
          // The existing finder also matches legacy rows that have resource_type_id or
          // resource_id set. Treat those as "different scope" so the check only blocks
          // truly-unscoped duplicates.
          .filter(existing -> existing.getResource() == null && existing.getResourceType() == null)
          .ifPresent(existing -> {
            throw new BusinessRuleException("NORM_SCOPE_DUPLICATE_UNSCOPED",
                "An unscoped " + r.normType()
                    + " productivity norm already exists for this Work Activity. Delete the "
                    + "existing one first, or use Variant scope (Role + skill/grade or make/model) "
                    + "to differentiate.");
          });
    }
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  private WorkActivity resolveWorkActivity(CreateProductivityNormRequest r) {
    if (r.workActivityId() != null) {
      return workActivityRepository.findById(r.workActivityId())
          .orElseThrow(() -> new ResourceNotFoundException("WorkActivity", r.workActivityId()));
    }
    if (r.activityName() != null && !r.activityName().isBlank()) {
      return workActivityRepository.findByNameIgnoreCase(r.activityName().trim())
          .orElseThrow(() -> new BusinessRuleException("UNKNOWN_WORK_ACTIVITY",
              "No WorkActivity matches activityName '" + r.activityName()
                  + "'. Create the WorkActivity first or supply workActivityId."));
    }
    throw new BusinessRuleException("WORK_ACTIVITY_REQUIRED",
        "workActivityId is required (or supply activityName matching an existing WorkActivity)");
  }
}
