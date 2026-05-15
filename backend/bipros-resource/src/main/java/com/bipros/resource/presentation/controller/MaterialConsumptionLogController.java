package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateMaterialConsumptionLogRequest;
import com.bipros.resource.application.dto.MaterialConsumptionLogResponse;
import com.bipros.resource.application.service.MaterialConsumptionLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/material-consumption")
@RequiredArgsConstructor
@Slf4j
public class MaterialConsumptionLogController {

  private final MaterialConsumptionLogService service;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<MaterialConsumptionLogResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateMaterialConsumptionLogRequest request) {
    log.info("POST /v1/projects/{}/material-consumption", projectId);
    MaterialConsumptionLogResponse response = service.create(projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<List<MaterialConsumptionLogResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateMaterialConsumptionLogRequest> requests) {
    log.info("POST /v1/projects/{}/material-consumption/bulk, count={}", projectId,
        requests != null ? requests.size() : 0);
    List<MaterialConsumptionLogResponse> response = service.createBulk(projectId, requests);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<List<MaterialConsumptionLogResponse>>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    log.info("GET /v1/projects/{}/material-consumption, from={}, to={}", projectId, from, to);
    return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, from, to)));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<MaterialConsumptionLogResponse>> get(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    log.info("GET /v1/projects/{}/material-consumption/{}", projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    log.info("DELETE /v1/projects/{}/material-consumption/{}", projectId, id);
    service.delete(projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
