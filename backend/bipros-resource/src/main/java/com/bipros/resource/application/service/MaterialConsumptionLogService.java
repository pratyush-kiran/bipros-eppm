package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.MaterialConsumptionLoggedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.application.dto.CreateMaterialConsumptionLogRequest;
import com.bipros.resource.application.dto.MaterialConsumptionLogResponse;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.rate.MaterialRateMaster;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.MaterialRateMasterRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class MaterialConsumptionLogService {

  private final MaterialConsumptionLogRepository repository;
  private final ProjectRepository projectRepository;
  private final ActivityRepository activityRepository;
  private final ResourceRepository resourceRepository;
  private final MaterialRateMasterRepository materialRateMasterRepository;
  private final AuditService auditService;
  private final SecurityContextHelper securityContextHelper;
  private final ApplicationEventPublisher eventPublisher;

  public MaterialConsumptionLogResponse create(
      UUID projectId, CreateMaterialConsumptionLogRequest request) {
    log.info(
        "Creating material consumption log: projectId={}, logDate={}, material={}",
        projectId,
        request.logDate(),
        request.materialName());

    if (projectId == null) {
      throw new BusinessRuleException("PROJECT_ID_REQUIRED", "projectId is required");
    }
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }

    if (request.activityId() != null) {
      Activity activity = activityRepository.findById(request.activityId())
          .orElseThrow(() -> new ResourceNotFoundException("Activity", request.activityId()));
      if (!projectId.equals(activity.getProjectId())) {
        throw new BusinessRuleException("ACTIVITY_PROJECT_MISMATCH",
            "Activity " + request.activityId() + " does not belong to project " + projectId);
      }
    }

    BigDecimal opening = request.openingStock();
    BigDecimal received = request.received() != null ? request.received() : BigDecimal.ZERO;
    BigDecimal consumed = request.consumed() != null ? request.consumed() : BigDecimal.ZERO;
    BigDecimal closing = opening.add(received).subtract(consumed);

    // Look up the active MaterialRateMaster row via the linked Resource (Resource.rateMasterId
    // points at material_rate_masters.id for MATERIAL-type resources). Compute line_cost only
    // when both consumed and rate are positive.
    UUID rateMasterId = null;
    BigDecimal unitRate = null;
    BigDecimal lineCost = null;
    if (request.resourceId() != null) {
      Optional<Resource> resourceOpt = resourceRepository.findById(request.resourceId());
      if (resourceOpt.isPresent() && resourceOpt.get().getRateMasterId() != null) {
        Optional<MaterialRateMaster> rateOpt =
            materialRateMasterRepository.findById(resourceOpt.get().getRateMasterId());
        if (rateOpt.isPresent() && Boolean.TRUE.equals(rateOpt.get().getActive())) {
          MaterialRateMaster rate = rateOpt.get();
          rateMasterId = rate.getId();
          unitRate = rate.getRate();
          lineCost = computeLineCost(consumed, unitRate);
        }
      }
    }

    // Caller-supplied enteredByRole wins (e.g. UI explicit "Storekeeper view"); fall back
    // to deriving from the authenticated principal when the request did not set it.
    String enteredByRole = request.enteredByRole();
    if (enteredByRole == null || enteredByRole.isBlank()) {
      enteredByRole = resolveEnteredByRole();
    }

    MaterialConsumptionLog entity =
        MaterialConsumptionLog.builder()
            .projectId(projectId)
            .logDate(request.logDate())
            .resourceId(request.resourceId())
            .materialName(request.materialName())
            .unit(request.unit())
            .openingStock(opening)
            .received(received)
            .consumed(consumed)
            .closingStock(closing)
            .wastagePercent(request.wastagePercent())
            .issuedBy(request.issuedBy())
            .receivedBy(request.receivedBy())
            .issuedByUserId(request.issuedByUserId())
            .receivedByUserId(request.receivedByUserId())
            .wbsNodeId(request.wbsNodeId())
            .activityId(request.activityId())
            .unitRate(unitRate)
            .lineCost(lineCost)
            .materialRateMasterId(rateMasterId)
            .enteredByRole(enteredByRole)
            .remarks(request.remarks())
            .build();

    MaterialConsumptionLog saved = repository.save(entity);
    log.info("Material consumption log created: id={}", saved.getId());

    auditService.logCreate(
        "MaterialConsumptionLog", saved.getId(), MaterialConsumptionLogResponse.from(saved));

    eventPublisher.publishEvent(new MaterialConsumptionLoggedEvent(
        saved.getProjectId(),
        saved.getId(),
        saved.getLogDate(),
        saved.getActivityId(),
        saved.getWbsNodeId(),
        saved.getMaterialRateMasterId(),
        saved.getIssuedByUserId(),
        saved.getReceivedByUserId(),
        saved.getLineCost(),
        MaterialConsumptionLoggedEvent.EventType.CREATED));

    return MaterialConsumptionLogResponse.from(saved);
  }

  public List<MaterialConsumptionLogResponse> createBulk(
      UUID projectId, List<CreateMaterialConsumptionLogRequest> requests) {
    log.info(
        "Bulk creating material consumption logs: projectId={}, count={}",
        projectId,
        requests != null ? requests.size() : 0);

    List<MaterialConsumptionLogResponse> results = new ArrayList<>();
    if (requests == null || requests.isEmpty()) {
      return results;
    }
    for (CreateMaterialConsumptionLogRequest req : requests) {
      results.add(create(projectId, req));
    }
    return results;
  }

  @Transactional(readOnly = true)
  public List<MaterialConsumptionLogResponse> list(
      UUID projectId, LocalDate from, LocalDate to) {
    log.info(
        "Listing material consumption logs: projectId={}, from={}, to={}", projectId, from, to);

    List<MaterialConsumptionLog> entities;
    if (from != null && to != null) {
      entities =
          repository.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(projectId, from, to);
    } else {
      entities = repository.findByProjectIdOrderByLogDateAscIdAsc(projectId);
    }
    return entities.stream().map(MaterialConsumptionLogResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public MaterialConsumptionLogResponse get(UUID projectId, UUID id) {
    log.info("Fetching material consumption log: projectId={}, id={}", projectId, id);
    MaterialConsumptionLog entity =
        repository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("MaterialConsumptionLog", id));
    if (!entity.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("MaterialConsumptionLog", id);
    }
    return MaterialConsumptionLogResponse.from(entity);
  }

  public void delete(UUID projectId, UUID id) {
    log.info("Deleting material consumption log: projectId={}, id={}", projectId, id);
    MaterialConsumptionLog entity =
        repository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("MaterialConsumptionLog", id));
    if (!entity.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("MaterialConsumptionLog", id);
    }
    repository.delete(entity);
    auditService.logDelete("MaterialConsumptionLog", id);

    eventPublisher.publishEvent(new MaterialConsumptionLoggedEvent(
        entity.getProjectId(),
        entity.getId(),
        entity.getLogDate(),
        entity.getActivityId(),
        entity.getWbsNodeId(),
        entity.getMaterialRateMasterId(),
        entity.getIssuedByUserId(),
        entity.getReceivedByUserId(),
        entity.getLineCost(),
        MaterialConsumptionLoggedEvent.EventType.DELETED));
  }

  /**
   * Stamp which role the caller holds. SUPERVISOR wins over STORE_MANAGER when both are present
   * (DPR-style entries are conceptually supervisor-owned). Anonymous / system writes return null.
   */
  private String resolveEnteredByRole() {
    try {
      if (securityContextHelper.hasRole("SUPERVISOR")) return "SUPERVISOR";
      if (securityContextHelper.hasRole("STORE_MANAGER")) return "STORE_MANAGER";
    } catch (Exception e) {
      log.debug("No authenticated user when stamping entered_by_role: {}", e.getMessage());
    }
    return null;
  }

  /** Same shape as {@code DprCostFormulas.materialLineCost}: {@code rate × consumed}, 2 dp. */
  private static BigDecimal computeLineCost(BigDecimal consumed, BigDecimal unitRate) {
    if (consumed == null || unitRate == null) return null;
    return unitRate.multiply(consumed).setScale(2, RoundingMode.HALF_UP);
  }
}
