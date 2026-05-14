package com.bipros.resource.application.service.role;

import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Role-keyed productivity norm resolver. Replaces the type-keyed lookup in the new role-only
 * model. Three-tier chain:
 *
 * <ol>
 *   <li>Exact variant: (workActivity, role, skillLevel, grade) for manpower,
 *       (workActivity, role, make, model) for equipment.
 *   <li>Role-only: (workActivity, role).
 *   <li>Unscoped: (workActivity) — the fallback that exists in legacy data too.
 * </ol>
 *
 * <p>{@code resolveByRoleVariant} is the canonical entrypoint. Legacy callers (capacity report,
 * supervisor performance) will be repointed in phase 6 follow-up.
 */
@Service
@RequiredArgsConstructor
public class RoleProductivityNormResolver {

  private final ProductivityNormRepository repo;

  public Optional<ProductivityNorm> resolveByRole(
      UUID workActivityId,
      UUID roleId,
      UUID categoryId,
      UUID gradeId,
      String make,
      String model,
      ProductivityNormType normType) {
    if (workActivityId == null || normType == null) return Optional.empty();
    if (roleId != null) {
      Optional<ProductivityNorm> exact =
          repo
              .findFirstByWorkActivityIdAndRoleIdAndCategoryIdAndGradeIdAndMakeAndModelAndNormType(
                  workActivityId, roleId, categoryId, gradeId, make, model, normType);
      if (exact.isPresent()) return exact;

      Optional<ProductivityNorm> roleOnly =
          repo
              .findFirstByWorkActivityIdAndRoleIdAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
                  workActivityId, roleId, normType);
      if (roleOnly.isPresent()) return roleOnly;
    }
    return repo
        .findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
            workActivityId, normType);
  }
}
