package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.Snag;
import com.bipros.siteops.domain.model.SnagStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SnagRepository extends JpaRepository<Snag, UUID> {
    List<Snag> findByProjectIdOrderByRaisedAtDesc(UUID projectId);

    List<Snag> findByProjectIdAndStatusOrderByRaisedAtDesc(UUID projectId, SnagStatus status);
}
