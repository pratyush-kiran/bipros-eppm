package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HdsChunkRepository extends JpaRepository<HdsChunk, UUID> {
    long countByHdsVersionId(UUID hdsVersionId);
    void deleteByHdsVersionId(UUID hdsVersionId);
}
