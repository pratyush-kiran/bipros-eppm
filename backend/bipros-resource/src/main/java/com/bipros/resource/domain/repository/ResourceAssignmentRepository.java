package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.ResourceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceAssignmentRepository extends JpaRepository<ResourceAssignment, UUID> {

  List<ResourceAssignment> findByActivityId(UUID activityId);

  long countByManpowerRoleRateId(UUID manpowerRoleRateId);

  long countByEquipmentRoleVariantId(UUID equipmentRoleVariantId);

  long countByMaterialRoleVariantId(UUID materialRoleVariantId);

  List<ResourceAssignment> findByActivityIdIn(List<UUID> activityIds);

  List<ResourceAssignment> findByResourceId(UUID resourceId);

  List<ResourceAssignment> findByProjectId(UUID projectId);

  List<ResourceAssignment> findByResourceIdAndPlannedStartDateBetween(
      UUID resourceId, LocalDate startDate, LocalDate endDate);

  Optional<ResourceAssignment> findByProjectIdAndActivityIdAndResourceId(
      UUID projectId, UUID activityId, UUID resourceId);

  Optional<ResourceAssignment> findByActivityIdAndResourceIdIsNullAndRoleId(
      UUID activityId, UUID roleId);

  Optional<ResourceAssignment> findFirstByActivityIdAndRoleIdAndManpowerRoleRateId(
      UUID activityId, UUID roleId, UUID manpowerRoleRateId);

  Optional<ResourceAssignment> findFirstByActivityIdAndRoleIdAndEquipmentRoleVariantId(
      UUID activityId, UUID roleId, UUID equipmentRoleVariantId);

  Optional<ResourceAssignment> findFirstByActivityIdAndRoleIdAndMaterialRoleVariantId(
      UUID activityId, UUID roleId, UUID materialRoleVariantId);

  List<ResourceAssignment> findByProjectIdAndResourceId(UUID projectId, UUID resourceId);

  @Query("select coalesce(sum(ra.plannedUnits), 0) from ResourceAssignment ra where ra.activityId = :activityId")
  Double sumPlannedUnitsByActivityId(@Param("activityId") UUID activityId);

  @Query(value = "SELECT DISTINCT project_id, resource_id FROM resource.resource_assignments WHERE resource_id IS NOT NULL", nativeQuery = true)
  List<Object[]> findDistinctProjectResourcePairs();

  @Query("select coalesce(sum(ra.actualUnits), 0) from ResourceAssignment ra where ra.activityId = :activityId")
  Double sumActualUnitsByActivityId(@Param("activityId") UUID activityId);
}
