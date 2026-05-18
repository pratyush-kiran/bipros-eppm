package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DailyResourceDeployment;
import com.bipros.project.domain.model.DeploymentResourceType;

import java.time.LocalDate;
import java.util.UUID;

public record DailyResourceDeploymentResponse(
    UUID id,
    UUID projectId,
    LocalDate logDate,
    DeploymentResourceType resourceType,
    String resourceDescription,
    UUID resourceId,
    UUID resourceRoleId,
    Integer nosPlanned,
    Boolean nosPlannedAuto,
    Integer nosDeployed,
    Double hoursWorked,
    Double idleHours,
    String remarks
) {
  public static DailyResourceDeploymentResponse from(DailyResourceDeployment r) {
    return new DailyResourceDeploymentResponse(
        r.getId(),
        r.getProjectId(),
        r.getLogDate(),
        r.getResourceType(),
        r.getResourceDescription(),
        r.getResourceId(),
        r.getResourceRoleId(),
        r.getNosPlanned(),
        r.getNosPlannedAuto(),
        r.getNosDeployed(),
        r.getHoursWorked(),
        r.getIdleHours(),
        r.getRemarks()
    );
  }
}
