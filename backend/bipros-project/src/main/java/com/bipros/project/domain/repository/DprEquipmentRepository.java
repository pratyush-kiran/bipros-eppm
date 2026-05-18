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

    @Query("""
        select coalesce(sum(e.lineCost), 0)
        from DprEquipment e, com.bipros.project.domain.model.DailyProgressReport d
        where e.dprId = d.id
          and d.projectId = :projectId
        """)
    BigDecimal sumLineCostByProject(@Param("projectId") UUID projectId);
}
