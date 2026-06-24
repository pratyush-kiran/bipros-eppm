package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID>, JpaSpecificationExecutor<Project> {

    List<Project> findByEpsNodeId(UUID epsNodeId);

    /** Number of projects (active or archived) assigned to an OBS node — used to guard OBS deletion. */
    long countByObsNodeId(UUID obsNodeId);

    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);

    boolean existsByCode(String code);

    Optional<Project> findByCode(String code);

    /**
     * All non-archived projects. Use this from reporting / dashboard rollups so that archived
     * (soft-deleted) projects don't leak into portfolio scorecards, EVM aggregates, RAG bands,
     * etc. Plain {@link #findAll()} still returns archived rows too — leave it for code paths
     * that legitimately need the full history (e.g. boot-time backfills).
     */
    List<Project> findAllByArchivedAtIsNull();
}
