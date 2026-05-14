package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EquipmentRoleVariantRepository extends JpaRepository<EquipmentRoleVariant, UUID> {

  List<EquipmentRoleVariant> findByRoleIdAndActiveTrue(UUID roleId);

  List<EquipmentRoleVariant> findByRoleIdInAndActiveTrue(List<UUID> roleIds);

  Optional<EquipmentRoleVariant> findByRoleIdAndMakeAndModel(UUID roleId, String make, String model);

  long countByRoleId(UUID roleId);
}
