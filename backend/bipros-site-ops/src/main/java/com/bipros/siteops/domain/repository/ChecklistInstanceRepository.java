package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.ChecklistInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChecklistInstanceRepository extends JpaRepository<ChecklistInstance, UUID> {

    List<ChecklistInstance> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
