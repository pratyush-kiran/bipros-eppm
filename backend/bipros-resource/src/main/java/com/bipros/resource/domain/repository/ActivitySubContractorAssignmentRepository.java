package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ActivitySubContractorAssignmentRepository
    extends JpaRepository<ActivitySubContractorAssignment, UUID> {

  List<ActivitySubContractorAssignment> findByProjectIdAndActivityId(UUID projectId, UUID activityId);

  List<ActivitySubContractorAssignment> findByProjectId(UUID projectId);

  long countBySubContractorMasterId(UUID subContractorMasterId);

  @Query("SELECT COALESCE(SUM(a.plannedCost), 0) FROM ActivitySubContractorAssignment a "
       + "WHERE a.projectId = :projectId")
  BigDecimal sumPlannedCostByProjectId(@Param("projectId") UUID projectId);
}
