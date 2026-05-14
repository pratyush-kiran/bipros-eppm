package com.bipros.resource.domain.repository.role;

import com.bipros.resource.domain.model.role.ProjectManpowerRoleRateOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectManpowerRoleRateOverrideRepository
    extends JpaRepository<ProjectManpowerRoleRateOverride, UUID> {

  Optional<ProjectManpowerRoleRateOverride>
      findByProjectIdAndManpowerRoleRateIdAndActiveTrue(UUID projectId, UUID manpowerRoleRateId);

  List<ProjectManpowerRoleRateOverride> findByProjectIdAndActiveTrue(UUID projectId);
}
