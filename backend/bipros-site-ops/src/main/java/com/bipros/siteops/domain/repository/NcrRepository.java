package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.Ncr;
import com.bipros.siteops.domain.model.NcrStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NcrRepository extends JpaRepository<Ncr, UUID> {

    List<Ncr> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<Ncr> findByProjectIdAndStatusOrderByCreatedAtDesc(UUID projectId, NcrStatus status);

    long countByProjectId(UUID projectId);
}
