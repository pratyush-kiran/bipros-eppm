package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateMaterialReconciliationRequest;
import com.bipros.resource.application.dto.MaterialReconciliationResponse;
import com.bipros.resource.application.service.MaterialReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/material-reconciliation")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class MaterialReconciliationController {

  private final MaterialReconciliationService materialReconciliationService;

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping
  public ResponseEntity<ApiResponse<MaterialReconciliationResponse>> createMaterialReconciliation(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateMaterialReconciliationRequest request) {
    log.info("POST /v1/projects/{}/material-reconciliation - Creating material reconciliation", projectId);
    MaterialReconciliationResponse response = materialReconciliationService.createReconciliation(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<List<MaterialReconciliationResponse>>> getMaterialReconciliations(
      @PathVariable UUID projectId,
      @RequestParam(required = false) String period) {
    log.info(
        "GET /v1/projects/{}/material-reconciliation - Fetching reconciliations, period={}",
        projectId,
        period);

    List<MaterialReconciliationResponse> response;
    if (period != null) {
      response = materialReconciliationService.getByProject(projectId, period);
    } else {
      List<MaterialReconciliationResponse> allReconciliations =
          materialReconciliationService.getByProject(projectId, null);
      response = allReconciliations;
    }

    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/resource/{resourceId}")
  public ResponseEntity<ApiResponse<List<MaterialReconciliationResponse>>> getByResource(
      @PathVariable UUID projectId,
      @PathVariable UUID resourceId) {
    log.info("GET /v1/projects/{}/material-reconciliation/resource/{} - Fetching reconciliations", projectId, resourceId);
    List<MaterialReconciliationResponse> response = materialReconciliationService.getByResource(resourceId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<MaterialReconciliationResponse>> getById(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    log.info("GET /v1/projects/{}/material-reconciliation/{} - Fetching reconciliation", projectId, id);
    MaterialReconciliationResponse response = materialReconciliationService.getById(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
