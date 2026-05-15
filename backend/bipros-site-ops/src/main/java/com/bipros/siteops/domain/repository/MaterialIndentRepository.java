package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.IndentStatus;
import com.bipros.siteops.domain.model.MaterialIndent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaterialIndentRepository extends JpaRepository<MaterialIndent, UUID> {

    List<MaterialIndent> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<MaterialIndent> findByProjectIdAndStatusOrderByCreatedAtDesc(UUID projectId, IndentStatus status);

    long countByProjectId(UUID projectId);
}
