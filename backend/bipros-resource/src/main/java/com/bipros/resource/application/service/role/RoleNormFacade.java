package com.bipros.resource.application.service.role;

import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Public-facing facade over {@link RoleProductivityNormResolver}. Adapts legacy callers
 * (resource-keyed and type-keyed lookups) onto the new role-keyed chain.
 *
 * <p>Use this class instead of the resolver directly when:
 * <ul>
 *   <li>You have a {@code resourceId} from a legacy DPR rollup row and need a norm —
 *       {@link #resolveForResource(UUID, UUID, ProductivityNormType)} loads the resource's role
 *       and delegates.
 *   <li>You only have a {@code resourceTypeId} (supervisor-performance type rollup) —
 *       {@link #resolveForResourceType(UUID, UUID, ProductivityNormType)} falls through to the
 *       unscoped tier since role-keyed norms can't be matched without a role.
 *   <li>You already have a role + variant from a role assignment —
 *       {@link #resolveForRole(UUID, UUID, UUID, UUID, String, String, ProductivityNormType)} is
 *       the direct entry point.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoleNormFacade {

  private final RoleProductivityNormResolver resolver;
  private final ResourceRepository resourceRepository;

  public NormBudgeted resolveForRole(
      UUID workActivityId,
      UUID roleId,
      UUID categoryId,
      UUID gradeId,
      String make,
      String model,
      ProductivityNormType normType) {
    return resolver.resolveAsBudgeted(
        workActivityId, roleId, categoryId, gradeId, make, model, normType);
  }

  /**
   * Legacy entry point — given a {@code resourceId} from a DPR rollup row, look up the resource's
   * role and chain through the role resolver. Returns {@link NormBudgeted#none()} when the
   * resource is missing or has no role (truly orphan legacy data).
   */
  public NormBudgeted resolveForResource(
      UUID workActivityId, UUID resourceId, ProductivityNormType normType) {
    if (workActivityId == null || normType == null) return NormBudgeted.none();
    if (resourceId == null) {
      // No resource context — only the unscoped tier can match.
      return resolver.resolveAsBudgeted(workActivityId, null, null, null, null, null, normType);
    }
    return resourceRepository
        .findById(resourceId)
        .map(
            r ->
                resolver.resolveAsBudgeted(
                    workActivityId,
                    roleIdOf(r),
                    null,
                    null,
                    null,
                    null,
                    normType))
        .orElseGet(
            () ->
                resolver.resolveAsBudgeted(
                    workActivityId, null, null, null, null, null, normType));
  }

  /**
   * Type-level lookups can only match the unscoped tier in the new role-keyed model — there's no
   * way to derive a role from "MANPOWER as a whole." Kept as a thin shim so callers that only
   * have a type don't have to know the chain.
   */
  public NormBudgeted resolveForResourceType(
      UUID workActivityId, UUID resourceTypeId, ProductivityNormType normType) {
    // resourceTypeId is intentionally unused — the new model has no type-keyed tier. The unscoped
    // fallback is what these callers were effectively getting before for any row that lacked a
    // specific-resource override.
    return resolver.resolveAsBudgeted(workActivityId, null, null, null, null, null, normType);
  }

  private static UUID roleIdOf(Resource r) {
    return r.getRole() != null ? r.getRole().getId() : null;
  }
}
