package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DprEquipmentRepository extends JpaRepository<DprEquipment, UUID> {

    List<DprEquipment> findByDprIdOrderByEquipmentTypeAsc(UUID dprId);

    List<DprEquipment> findByDprIdIn(Collection<UUID> dprIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByDprId(UUID dprId);

    /** See {@code DprManpowerRepository#sumLineCostByProjectAndActivity}. */
    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id
          and d.projectId = :projectId
          and d.activityId = :activityId
        """)
    BigDecimal sumLineCostByProjectAndActivity(@Param("projectId") UUID projectId,
                                               @Param("activityId") UUID activityId);

    /** APPROVED-only variant — same as {@link #sumLineCostByProjectAndActivity} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id
          and d.projectId = :projectId
          and d.activityId = :activityId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    BigDecimal sumLineCostByProjectAndActivityApproved(@Param("projectId") UUID projectId,
                                                       @Param("activityId") UUID activityId);

    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id
          and d.projectId = :projectId
        """)
    BigDecimal sumLineCostByProject(@Param("projectId") UUID projectId);

    /** APPROVED-only variant — same as {@link #sumLineCostByProject} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id
          and d.projectId = :projectId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    BigDecimal sumLineCostByProjectApproved(@Param("projectId") UUID projectId);

    /** Per-DPR sum of equipment count (nos) for the given dpr ids. Returns [dprId (UUID), total (Long)]. */
    @Query("select e.dprId, coalesce(sum(e.nos), 0) from DprEquipment e where e.dprId in :ids group by e.dprId")
    List<Object[]> sumNosByDprIdIn(@Param("ids") Collection<UUID> ids);

    // ---- From-scratch rebuild queries (Task 1: DPR Data Repair) ----

    /** All equipment rows for a DPR — used by data-repair to inspect/rebuild resource lines. */
    List<DprEquipment> findByDprId(UUID dprId);

    /**
     * Absolute sum of equipment line_cost across all DPRs for a (project, boqItem).
     * Used by the from-scratch BOQ actualRate rebuild.
     */
    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id and d.projectId = :projectId and d.boqItemId = :boqItemId
        """)
    java.math.BigDecimal sumLineCostByBoqItemId(
        @Param("projectId") UUID projectId,
        @Param("boqItemId") UUID boqItemId);

    /** APPROVED-only variant — same as {@link #sumLineCostByBoqItemId} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id and d.projectId = :projectId and d.boqItemId = :boqItemId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    java.math.BigDecimal sumLineCostByBoqItemIdApproved(
        @Param("projectId") UUID projectId,
        @Param("boqItemId") UUID boqItemId);
}
