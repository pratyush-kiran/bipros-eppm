package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.ProjectMaterialRoleVariantOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectMaterialRoleVariantOverrideRepository
    extends JpaRepository<ProjectMaterialRoleVariantOverride, UUID> {

  Optional<ProjectMaterialRoleVariantOverride>
      findByProjectIdAndMaterialRoleVariantIdAndActiveTrue(
          UUID projectId, UUID materialRoleVariantId);

  List<ProjectMaterialRoleVariantOverride> findByProjectIdAndActiveTrue(UUID projectId);
}
