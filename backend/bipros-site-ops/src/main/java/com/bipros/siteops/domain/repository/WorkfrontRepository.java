package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.Workfront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkfrontRepository extends JpaRepository<Workfront, UUID> {
    List<Workfront> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
