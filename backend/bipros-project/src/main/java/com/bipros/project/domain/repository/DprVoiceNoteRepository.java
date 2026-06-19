package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprVoiceNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DprVoiceNoteRepository extends JpaRepository<DprVoiceNote, UUID> {

    List<DprVoiceNote> findByDprIdOrderByCreatedAtAsc(UUID dprId);

    /** Batch fetch for the list endpoint — avoids an N+1 across DPR rows. */
    List<DprVoiceNote> findByDprIdIn(Collection<UUID> dprIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByDprId(UUID dprId);

    /** Per-DPR voice-note count. Returns [dprId (UUID), count (Long)]. */
    @Query("select v.dprId, count(v) from DprVoiceNote v where v.dprId in :ids group by v.dprId")
    List<Object[]> countByDprIdIn(@Param("ids") Collection<UUID> ids);
}
