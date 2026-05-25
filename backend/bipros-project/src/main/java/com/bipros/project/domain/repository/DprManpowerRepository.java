package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprManpower;
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
public interface DprManpowerRepository extends JpaRepository<DprManpower, UUID> {

    List<DprManpower> findByDprIdOrderByTradeAsc(UUID dprId);

    /** Batch fetch for the list endpoint — avoids an N+1 across DPR rows. */
    List<DprManpower> findByDprIdIn(Collection<UUID> dprIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByDprId(UUID dprId);

    /**
     * Sum of {@code line_cost} for all manpower rows belonging to DPRs of the given
     * (project, activity). Used by EVM {@code getActivityAc} and Cost {@code getCostSummary}
     * to surface the persisted DPR cost as Actual Cost without a parallel rollup table.
     * Returns null when there are no rows; callers must coalesce.
     */
    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprManpower m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
          and d.activityId = :activityId
        """)
    BigDecimal sumLineCostByProjectAndActivity(@Param("projectId") UUID projectId,
                                               @Param("activityId") UUID activityId);

    /** Project-level total used by Cost summary. */
    @Query("""
        select coalesce(sum(m.lineCost), 0)
        from DprManpower m, com.bipros.project.domain.model.DailyProgressReport d
        where m.dprId = d.id
          and d.projectId = :projectId
        """)
    BigDecimal sumLineCostByProject(@Param("projectId") UUID projectId);

    /** Per-DPR sum of manpower headcount (nos) for the given dpr ids. Returns [dprId (UUID), total (Long)]. */
    @Query("select m.dprId, coalesce(sum(m.nos), 0) from DprManpower m where m.dprId in :ids group by m.dprId")
    List<Object[]> sumNosByDprIdIn(@Param("ids") Collection<UUID> ids);
}
