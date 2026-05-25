package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DprAttachmentRepository extends JpaRepository<DprAttachment, UUID> {

    List<DprAttachment> findByDprIdOrderByCreatedAtAsc(UUID dprId);

    /** Batch fetch for the list endpoint — avoids an N+1 across DPR rows. */
    List<DprAttachment> findByDprIdIn(Collection<UUID> dprIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByDprId(UUID dprId);

    /** Per-DPR photo/attachment count. Returns [dprId (UUID), count (Long)]. */
    @Query("select a.dprId, count(a) from DprAttachment a where a.dprId in :ids group by a.dprId")
    List<Object[]> countByDprIdIn(@Param("ids") Collection<UUID> ids);
}
