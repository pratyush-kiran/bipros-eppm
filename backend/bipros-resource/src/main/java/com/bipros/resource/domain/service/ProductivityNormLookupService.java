package com.bipros.resource.domain.service;

import com.bipros.resource.application.service.role.RoleProductivityNormResolver;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the effective productivity norm for a {@code (workActivity, resource)} pair.
 *
 * <p>Fallback order (post role-only migration):
 * <ol>
 *   <li>role-keyed norm (variant tier inside {@link RoleProductivityNormResolver})</li>
 *   <li>norm scoped to the specific resource ({@code resource_id}) — legacy</li>
 *   <li>norm scoped to the resource's type ({@code resource_type_id}) — legacy</li>
 *   <li>unscoped (work-activity-only) — the 102 seeded rows</li>
 *   <li>empty</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ProductivityNormLookupService {

  private final ProductivityNormRepository normRepository;
  private final WorkActivityRepository workActivityRepository;
  private final ResourceRepository resourceRepository;
  private final RoleProductivityNormResolver roleResolver;

  public ResolvedNorm resolve(UUID workActivityId, UUID resourceId) {
    if (workActivityId == null) {
      return ResolvedNorm.none(null, resourceId);
    }
    Resource resource = resourceId == null ? null : resourceRepository.findById(resourceId).orElse(null);
    ProductivityNormType normType = inferNormType(resource);

    // 1) Role-keyed (variant → role → unscoped via the role resolver). If the resource has a
    //    role, this catches both the role-only and unscoped tiers in one call. We still fall
    //    through to the legacy specific/type tiers below for older rows.
    ResourceRole role = resource == null ? null : resource.getRole();
    if (role != null && normType != null) {
      Optional<ProductivityNorm> roleMatch =
          roleResolver.resolveByRole(workActivityId, role.getId(), null, null, null, null, normType);
      if (roleMatch.isPresent()) {
        // VARIANT tier needs category/grade/make/model on input; we don't have them here, so
        // anything that matches via roleResolver lands on ROLE or UNSCOPED.
        boolean isUnscoped = roleMatch.get().getRoleId() == null;
        ResolvedNorm.Source source =
            isUnscoped ? ResolvedNorm.Source.UNSCOPED : ResolvedNorm.Source.ROLE;
        return materialise(roleMatch.get(), source, resourceId);
      }
    }

    if (resource != null) {
      Optional<ProductivityNorm> specific =
          normRepository.findFirstByWorkActivityIdAndResourceId(workActivityId, resource.getId());
      if (specific.isPresent()) {
        return materialise(specific.get(), ResolvedNorm.Source.SPECIFIC_RESOURCE, resource.getId());
      }
      ResourceType type = resource.getResourceType();
      if (type != null) {
        Optional<ProductivityNorm> typeLevel = normRepository
            .findFirstByWorkActivityIdAndResourceIsNullAndResourceTypeId(workActivityId, type.getId());
        if (typeLevel.isPresent()) {
          return materialise(typeLevel.get(), ResolvedNorm.Source.RESOURCE_TYPE, resource.getId());
        }
      }
    }

    // 4) Unscoped fallback even when we couldn't infer a role (no resource passed, or
    //    legacy data). Loop both norm types so callers without a normType still find a match.
    for (ProductivityNormType nt : ProductivityNormType.values()) {
      Optional<ProductivityNorm> unscoped = normRepository
          .findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
              workActivityId, nt);
      if (unscoped.isPresent()) {
        return materialise(unscoped.get(), ResolvedNorm.Source.UNSCOPED, resourceId);
      }
    }
    return ResolvedNorm.none(workActivityId, resourceId);
  }

  private static ProductivityNormType inferNormType(Resource resource) {
    if (resource == null || resource.getResourceType() == null) return null;
    String code = resource.getResourceType().getCode();
    if (code == null) return null;
    String upper = code.toUpperCase();
    if (upper.equals("EQUIPMENT") || upper.equals("MACHINE")) return ProductivityNormType.EQUIPMENT;
    if (upper.equals("MANPOWER") || upper.equals("LABOR")) return ProductivityNormType.MANPOWER;
    return null;
  }

  /** Convenience for callers that only have an activity name (e.g. seeders ingesting BOQ rows). */
  public ResolvedNorm resolveByName(String activityName, UUID resourceId) {
    if (activityName == null || activityName.isBlank()) {
      return ResolvedNorm.none(null, resourceId);
    }
    Optional<WorkActivity> wa = workActivityRepository.findByNameIgnoreCase(activityName.trim());
    return wa.map(w -> resolve(w.getId(), resourceId))
        .orElseGet(() -> ResolvedNorm.none(null, resourceId));
  }

  private ResolvedNorm materialise(ProductivityNorm norm, ResolvedNorm.Source source, UUID resourceId) {
    return new ResolvedNorm(
        norm.getOutputPerDay(),
        norm.getUnit(),
        source,
        norm.getId(),
        norm.getWorkActivity() == null ? null : norm.getWorkActivity().getId(),
        resourceId);
  }
}
