package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprMaterial;
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
public interface DprMaterialRepository extends JpaRepository<DprMaterial, UUID> {

    List<DprMaterial> findByDprIdOrderByMaterialNameAsc(UUID dprId);

    List<DprMaterial> findByDprIdIn(Collection<UUID> dprIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByDprId(UUID dprId);

    /** See {@code DprManpowerRepository#sumLineCostByProjectAndActivity}. */
    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprMaterial m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
          and d.activityId = :activityId
        """)
    BigDecimal sumLineCostByProjectAndActivity(@Param("projectId") UUID projectId,
                                               @Param("activityId") UUID activityId);

    /** APPROVED-only variant — same as {@link #sumLineCostByProjectAndActivity} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprMaterial m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
          and d.activityId = :activityId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    BigDecimal sumLineCostByProjectAndActivityApproved(@Param("projectId") UUID projectId,
                                                       @Param("activityId") UUID activityId);

    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprMaterial m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
        """)
    BigDecimal sumLineCostByProject(@Param("projectId") UUID projectId);

    /** APPROVED-only variant — same as {@link #sumLineCostByProject} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprMaterial m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    BigDecimal sumLineCostByProjectApproved(@Param("projectId") UUID projectId);

    /** Per-DPR material line count. Returns [dprId (UUID), count (Long)]. */
    @Query("select m.dprId, count(m) from DprMaterial m where m.dprId in :ids group by m.dprId")
    List<Object[]> countByDprIdIn(@Param("ids") Collection<UUID> ids);

    // ---- From-scratch rebuild queries (Task 1: DPR Data Repair) ----

    /** All material rows for a DPR — used by data-repair to inspect/rebuild resource lines. */
    List<DprMaterial> findByDprId(UUID dprId);

    /**
     * Absolute sum of material line_cost across all DPRs for a (project, boqItem).
     * Used by the from-scratch BOQ actualRate rebuild.
     */
    @Query("""
        select coalesce(sum(mt.lineCost), 0)
        from DprMaterial mt, com.bipros.project.domain.model.DailyProgressReport d
        where mt.dprId = d.id and d.projectId = :projectId and d.boqItemId = :boqItemId
        """)
    java.math.BigDecimal sumLineCostByBoqItemId(
        @Param("projectId") UUID projectId,
        @Param("boqItemId") UUID boqItemId);

    /** APPROVED-only variant — same as {@link #sumLineCostByBoqItemId} but restricted to APPROVED DPRs. */
    @Query("""
        select coalesce(sum(mt.lineCost), 0)
        from DprMaterial mt, com.bipros.project.domain.model.DailyProgressReport d
        where mt.dprId = d.id and d.projectId = :projectId and d.boqItemId = :boqItemId
          and d.approvalStatus = com.bipros.project.domain.model.DprApprovalStatus.APPROVED
        """)
    java.math.BigDecimal sumLineCostByBoqItemIdApproved(
        @Param("projectId") UUID projectId,
        @Param("boqItemId") UUID boqItemId);
}
