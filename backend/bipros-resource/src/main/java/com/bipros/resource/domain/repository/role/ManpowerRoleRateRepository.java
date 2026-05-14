package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ManpowerRoleRateRepository extends JpaRepository<ManpowerRoleRate, UUID> {

  List<ManpowerRoleRate> findByRoleIdAndActiveTrue(UUID roleId);

  List<ManpowerRoleRate> findByRoleIdInAndActiveTrue(List<UUID> roleIds);

  Optional<ManpowerRoleRate> findByRoleIdAndCategoryIdAndGradeId(
      UUID roleId, UUID categoryId, UUID gradeId);

  long countByRoleId(UUID roleId);

  long countByCategoryId(UUID categoryId);

  long countByGradeId(UUID gradeId);
}
