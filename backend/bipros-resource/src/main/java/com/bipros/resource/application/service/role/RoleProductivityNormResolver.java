package com.bipros.resource.application.service.role;

import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Role-keyed productivity norm resolver. Three-tier chain:
 *
 * <ol>
 *   <li>Exact variant: (workActivity, role, skillLevel, grade) for manpower,
 *       (workActivity, role, make, model) for equipment.
 *   <li>Role-only: (workActivity, role).
 *   <li>Unscoped: (workActivity) — the fallback that exists in legacy data too.
 * </ol>
 *
 * <p>Use {@link #resolveAsBudgeted} for the kind-agnostic {@link NormBudgeted} projection that
 * the report + DPR-preview callers consume. {@link #resolveByRole} returns the raw entity for
 * callers that need every column.
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

  /**
   * Same resolution chain but returns a {@link NormBudgeted} projection that tags the matched tier
   * in {@link NormBudgeted#source()} ({@code VARIANT} / {@code ROLE} / {@code UNSCOPED} / {@code NONE}).
   */
  public NormBudgeted resolveAsBudgeted(
      UUID workActivityId,
      UUID roleId,
      UUID categoryId,
      UUID gradeId,
      String make,
      String model,
      ProductivityNormType normType) {
    if (workActivityId == null || normType == null) return NormBudgeted.none();

    if (roleId != null) {
      Optional<ProductivityNorm> exact =
          repo
              .findFirstByWorkActivityIdAndRoleIdAndCategoryIdAndGradeIdAndMakeAndModelAndNormType(
                  workActivityId, roleId, categoryId, gradeId, make, model, normType);
      if (exact.isPresent()) return project(exact.get(), "VARIANT");

      Optional<ProductivityNorm> roleOnly =
          repo
              .findFirstByWorkActivityIdAndRoleIdAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
                  workActivityId, roleId, normType);
      if (roleOnly.isPresent()) return project(roleOnly.get(), "ROLE");
    }

    Optional<ProductivityNorm> unscoped =
        repo
            .findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
                workActivityId, normType);
    return unscoped.map(n -> project(n, "UNSCOPED")).orElse(NormBudgeted.none());
  }

  private static NormBudgeted project(ProductivityNorm n, String source) {
    return new NormBudgeted(
        n.getOutputPerDay(),
        n.getOutputPerManPerDay(),
        n.getOutputPerHour(),
        n.getWorkingHoursPerDay(),
        n.getId(),
        source,
        n.getNormType() != null ? n.getNormType().name() : null);
  }
}
