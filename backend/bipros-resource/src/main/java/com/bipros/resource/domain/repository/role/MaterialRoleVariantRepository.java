package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MaterialRoleVariantRepository extends JpaRepository<MaterialRoleVariant, UUID> {

  List<MaterialRoleVariant> findByRoleIdAndActiveTrue(UUID roleId);

  List<MaterialRoleVariant> findByRoleIdInAndActiveTrue(List<UUID> roleIds);

  Optional<MaterialRoleVariant> findByRoleIdAndSpecGrade(UUID roleId, String specGrade);

  long countByRoleId(UUID roleId);
}
