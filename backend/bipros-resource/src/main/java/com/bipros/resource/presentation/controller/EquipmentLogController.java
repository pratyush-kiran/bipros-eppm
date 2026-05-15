package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateEquipmentLogRequest;
import com.bipros.resource.application.dto.EquipmentLogResponse;
import com.bipros.resource.application.dto.EquipmentUtilizationSummary;
import com.bipros.resource.application.service.EquipmentLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/equipment-logs")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class EquipmentLogController {

  private final EquipmentLogService equipmentLogService;

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping
  public ResponseEntity<ApiResponse<EquipmentLogResponse>> createEquipmentLog(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateEquipmentLogRequest request) {
    log.info("POST /v1/projects/{}/equipment-logs - Creating equipment log", projectId);
    EquipmentLogResponse response = equipmentLogService.createLog(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<Page<EquipmentLogResponse>>> getEquipmentLogs(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID resourceId,
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    log.info(
        "GET /v1/projects/{}/equipment-logs - Fetching logs, resourceId={}, fromDate={}, toDate={}",
        projectId,
        resourceId,
        fromDate,
        toDate);

    Pageable pageable = PageRequest.of(page, size);
    LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusMonths(3);
    LocalDate to = toDate != null ? toDate : LocalDate.now();

    Page<EquipmentLogResponse> response;
    if (resourceId != null) {
      response = equipmentLogService.getLogsByResource(resourceId, from, to, pageable);
    } else {
      response = equipmentLogService.getLogsByProject(projectId, from, to, pageable);
    }

    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/utilization")
  public ResponseEntity<ApiResponse<List<EquipmentUtilizationSummary>>> getUtilizationSummary(
      @PathVariable UUID projectId) {
    log.info("GET /v1/projects/{}/equipment-logs/utilization - Computing utilization summary", projectId);
    List<EquipmentUtilizationSummary> response = equipmentLogService.getUtilizationSummary(projectId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
