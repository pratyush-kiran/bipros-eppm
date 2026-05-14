package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductivityNormRepository extends JpaRepository<ProductivityNorm, UUID> {

  List<ProductivityNorm> findByNormType(ProductivityNormType normType);

  /** @deprecated activity is now linked via {@code workActivity}; kept for legacy callers. */
  @Deprecated
  List<ProductivityNorm> findByActivityNameIgnoreCase(String activityName);

  List<ProductivityNorm> findByWorkActivityId(UUID workActivityId);

  long countByWorkActivityId(UUID workActivityId);

  Optional<ProductivityNorm> findFirstByWorkActivityIdAndResourceId(
      UUID workActivityId, UUID resourceId);

  Optional<ProductivityNorm> findFirstByWorkActivityIdAndResourceIsNullAndResourceTypeId(
      UUID workActivityId, UUID resourceTypeId);

  // ----- Role-keyed resolver chain (new model) -----

  Optional<ProductivityNorm>
      findFirstByWorkActivityIdAndRoleIdAndCategoryIdAndGradeIdAndMakeAndModelAndNormType(
          UUID workActivityId,
          UUID roleId,
          UUID categoryId,
          UUID gradeId,
          String make,
          String model,
          ProductivityNormType normType);

  Optional<ProductivityNorm>
      findFirstByWorkActivityIdAndRoleIdAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
          UUID workActivityId, UUID roleId, ProductivityNormType normType);

  Optional<ProductivityNorm>
      findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
          UUID workActivityId, ProductivityNormType normType);
}
