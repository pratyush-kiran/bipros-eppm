package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.ProjectRole;
import com.bipros.project.domain.model.ProjectTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectTeamRepository extends JpaRepository<ProjectTeamMember, UUID> {

    List<ProjectTeamMember> findByProjectId(UUID projectId);

    List<ProjectTeamMember> findByProjectIdAndRole(UUID projectId, ProjectRole role);

    Optional<ProjectTeamMember> findByProjectIdAndUserIdAndRole(UUID projectId, UUID userId, ProjectRole role);

    List<ProjectTeamMember> findByProjectIdAndReportsToUserId(UUID projectId, UUID reportsToUserId);
}
