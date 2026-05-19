package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ProjectTeamRepository extends JpaRepository<ProjectTeamMember, UUID> {

    List<ProjectTeamMember> findByProjectId(UUID projectId);

    List<ProjectTeamMember> findByProjectIdAndRole(UUID projectId, ProjectRole role);

    Optional<ProjectTeamMember> findByProjectIdAndUserIdAndRole(UUID projectId, UUID userId, ProjectRole role);

    List<ProjectTeamMember> findByProjectIdAndReportsToUserId(UUID projectId, UUID reportsToUserId);

    /**
     * Find every team-member row a user holds on a given project. A user can hold multiple
     * project roles (e.g. acting Engineer + Supervisor); the chain-walking helpers in
     * {@code ProjectTeamService} take the first row from this list to follow {@code reportsToUserId}.
     */
    List<ProjectTeamMember> findAllByProjectIdAndUserId(UUID projectId, UUID userId);

    /** Every team-row a user holds across all projects — used by access guard membership lookups. */
    List<ProjectTeamMember> findByUserId(UUID userId);

    /**
     * Distinct set of project IDs the user is on the team of (any role). Powers
     * {@code ProjectAccessService#getAccessibleProjectIds} so that project-team membership
     * grants READ alongside the legacy {@code project_members} table.
     */
    @Query("SELECT DISTINCT t.projectId FROM ProjectTeamMember t WHERE t.userId = ?1")
    Set<UUID> findProjectIdsByUserId(UUID userId);
}
