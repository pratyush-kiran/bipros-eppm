package com.bipros.evm.domain.repository;

import com.bipros.evm.domain.entity.EvmCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvmCalculationRepository extends JpaRepository<EvmCalculation, UUID> {
    /** All records for a project (project-level + WBS-node + activity rows), newest first. */
    List<EvmCalculation> findByProjectIdOrderByDataDateDesc(UUID projectId);

    /**
     * Latest project-level EVM calculation (wbsNodeId IS NULL AND activityId IS NULL).
     * Without this filter, findTop could return a WBS-node-level or activity-level row that
     * shares the same dataDate as the project row but has all-zero values.
     */
    @Query("""
            SELECT e FROM EvmCalculation e
            WHERE e.projectId = :projectId
              AND e.wbsNodeId IS NULL
              AND e.activityId IS NULL
            ORDER BY e.dataDate DESC, e.createdAt DESC
            """)
    List<EvmCalculation> findProjectLevelByProjectIdOrderByDataDateDesc(@Param("projectId") UUID projectId);

    default Optional<EvmCalculation> findTopByProjectIdOrderByDataDateDesc(UUID projectId) {
        List<EvmCalculation> rows = findProjectLevelByProjectIdOrderByDataDateDesc(projectId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    List<EvmCalculation> findByProjectIdAndDataDate(UUID projectId, LocalDate dataDate);
    List<EvmCalculation> findByProjectIdAndWbsNodeId(UUID projectId, UUID wbsNodeId);
    List<EvmCalculation> findByProjectIdAndWbsNodeIdOrderByDataDateDesc(UUID projectId, UUID wbsNodeId);
    Optional<EvmCalculation> findTopByProjectIdAndWbsNodeIdOrderByDataDateDesc(UUID projectId, UUID wbsNodeId);

    Optional<EvmCalculation> findTopByProjectIdAndActivityIdOrderByDataDateDesc(UUID projectId, UUID activityId);
}
