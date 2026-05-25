package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsIngestionJobRepository extends JpaRepository<HdsIngestionJob, UUID> {

    Optional<HdsIngestionJob> findFirstByStageInOrderByCreatedAtAsc(List<HdsIngestionStage> stages);

    Optional<HdsIngestionJob> findByHdsVersionId(UUID hdsVersionId);

    @Modifying
    @Query("UPDATE HdsIngestionJob j SET j.lastHeartbeatAt = :ts WHERE j.id = :id")
    int touchHeartbeat(@Param("id") UUID id, @Param("ts") Instant ts);

    @Query("SELECT j FROM HdsIngestionJob j " +
           "WHERE j.stage IN (com.bipros.hds.domain.enums.HdsIngestionStage.PARSING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.CHUNKING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.EMBEDDING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.INDEXING) " +
           "AND j.lastHeartbeatAt < :cutoff")
    List<HdsIngestionJob> findStaleJobs(@Param("cutoff") Instant cutoff);
}
