package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.ProjectEquipmentRoleVariantOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectEquipmentRoleVariantOverrideRepository
    extends JpaRepository<ProjectEquipmentRoleVariantOverride, UUID> {

  Optional<ProjectEquipmentRoleVariantOverride>
      findByProjectIdAndEquipmentRoleVariantIdAndActiveTrue(
          UUID projectId, UUID equipmentRoleVariantId);

  List<ProjectEquipmentRoleVariantOverride> findByProjectIdAndActiveTrue(UUID projectId);
}
