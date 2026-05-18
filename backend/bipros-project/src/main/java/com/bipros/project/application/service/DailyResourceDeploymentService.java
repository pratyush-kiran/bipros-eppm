package com.bipros.project.application.service;

import com.bipros.common.event.ResourceDeploymentSavedEvent;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyResourceDeploymentRequest;
import com.bipros.project.application.dto.DailyResourceDeploymentResponse;
import com.bipros.project.application.util.ResourceAssignmentPlannedHeadcountResolver;
import com.bipros.project.domain.model.DailyResourceDeployment;
import com.bipros.project.domain.model.DeploymentResourceType;
import com.bipros.project.domain.repository.DailyResourceDeploymentRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DailyResourceDeploymentService {

  private final DailyResourceDeploymentRepository repository;
  private final ProjectRepository projectRepository;
  private final AuditService auditService;
  private final ResourceAssignmentPlannedHeadcountResolver plannedHeadcountResolver;
  private final ApplicationEventPublisher eventPublisher;

  public DailyResourceDeploymentResponse create(UUID projectId, CreateDailyResourceDeploymentRequest request) {
    ensureProjectExists(projectId);

    // Treat null OR 0 as "not provided" for back-compat: the legacy UI submitted 0 when the user
    // left the field blank. When unset, try to derive nosPlanned from ResourceAssignment.
    Integer nosPlanned = request.nosPlanned();
    Boolean nosPlannedAuto = null;
    if (nosPlanned == null || nosPlanned == 0) {
      Optional<Integer> derived = plannedHeadcountResolver.resolve(
          projectId, request.resourceRoleId(), request.resourceType(), request.logDate());
      if (derived.isPresent()) {
        nosPlanned = derived.get();
        nosPlannedAuto = Boolean.TRUE;
      } else {
        // Leave nosPlanned at the request's value (null or 0); only stamp auto=false when the
        // user clearly typed a non-zero value below.
        nosPlannedAuto = null;
      }
    } else {
      nosPlannedAuto = Boolean.FALSE;
    }

    DailyResourceDeployment deployment = DailyResourceDeployment.builder()
        .projectId(projectId)
        .logDate(request.logDate())
        .resourceType(request.resourceType())
        .resourceDescription(request.resourceDescription())
        .resourceId(request.resourceId())
        .resourceRoleId(request.resourceRoleId())
        .nosPlanned(nosPlanned)
        .nosPlannedAuto(nosPlannedAuto)
        .nosDeployed(request.nosDeployed())
        .hoursWorked(request.hoursWorked())
        .idleHours(request.idleHours())
        .remarks(request.remarks())
        .build();

    DailyResourceDeployment saved = repository.save(deployment);
    auditService.logCreate("DailyResourceDeployment", saved.getId(), DailyResourceDeploymentResponse.from(saved));

    eventPublisher.publishEvent(new ResourceDeploymentSavedEvent(
        saved.getProjectId(),
        saved.getId(),
        saved.getLogDate(),
        saved.getResourceType() != null ? saved.getResourceType().name() : null,
        saved.getResourceId(),
        saved.getResourceRoleId(),
        ResourceDeploymentSavedEvent.EventType.CREATED));

    return DailyResourceDeploymentResponse.from(saved);
  }

  /**
   * Returns the auto-derived suggested {@code nosPlanned} for a (project, role, date) triple
   * without persisting anything — used by the "Recalculate from plan" button on the frontend
   * to refresh its preview before the user submits.
   */
  @Transactional(readOnly = true)
  public Integer suggestNosPlanned(
      UUID projectId, UUID resourceRoleId, DeploymentResourceType resourceType, LocalDate logDate) {
    ensureProjectExists(projectId);
    return plannedHeadcountResolver
        .resolve(projectId, resourceRoleId, resourceType, logDate)
        .orElse(null);
  }

  public List<DailyResourceDeploymentResponse> createBulk(UUID projectId, List<CreateDailyResourceDeploymentRequest> requests) {
    return requests.stream().map(r -> create(projectId, r)).toList();
  }

  @Transactional(readOnly = true)
  public List<DailyResourceDeploymentResponse> list(
      UUID projectId, LocalDate from, LocalDate to, DeploymentResourceType resourceType) {
    ensureProjectExists(projectId);
    List<DailyResourceDeployment> rows;
    if (from != null && to != null) {
      rows = repository.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(projectId, from, to);
    } else {
      rows = repository.findByProjectIdOrderByLogDateAscIdAsc(projectId);
    }
    if (resourceType != null) {
      rows = rows.stream().filter(r -> r.getResourceType() == resourceType).toList();
    }
    return rows.stream().map(DailyResourceDeploymentResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public DailyResourceDeploymentResponse get(UUID projectId, UUID id) {
    return DailyResourceDeploymentResponse.from(find(projectId, id));
  }

  public void delete(UUID projectId, UUID id) {
    DailyResourceDeployment deployment = find(projectId, id);
    repository.delete(deployment);
    auditService.logDelete("DailyResourceDeployment", id);

    eventPublisher.publishEvent(new ResourceDeploymentSavedEvent(
        deployment.getProjectId(),
        deployment.getId(),
        deployment.getLogDate(),
        deployment.getResourceType() != null ? deployment.getResourceType().name() : null,
        deployment.getResourceId(),
        deployment.getResourceRoleId(),
        ResourceDeploymentSavedEvent.EventType.DELETED));
  }

  private DailyResourceDeployment find(UUID projectId, UUID id) {
    DailyResourceDeployment deployment = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DailyResourceDeployment", id));
    if (!deployment.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("DailyResourceDeployment", id);
    }
    return deployment;
  }

  private void ensureProjectExists(UUID projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }
  }
}
