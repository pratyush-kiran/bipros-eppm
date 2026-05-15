package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateLabourReturnRequest;
import com.bipros.resource.application.dto.DeploymentSummary;
import com.bipros.resource.application.dto.LabourReturnResponse;
import com.bipros.resource.application.service.LabourReturnService;
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
@RequestMapping("/v1/projects/{projectId}/labour-returns")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class LabourReturnController {

  private final LabourReturnService labourReturnService;

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping
  public ResponseEntity<ApiResponse<LabourReturnResponse>> createLabourReturn(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateLabourReturnRequest request) {
    log.info("POST /v1/projects/{}/labour-returns - Creating labour return", projectId);
    LabourReturnResponse response = labourReturnService.createReturn(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<Page<LabourReturnResponse>>> getLabourReturns(
      @PathVariable UUID projectId,
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    log.info(
        "GET /v1/projects/{}/labour-returns - Fetching labour returns, fromDate={}, toDate={}",
        projectId,
        fromDate,
        toDate);

    Pageable pageable = PageRequest.of(page, size);
    LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusMonths(3);
    LocalDate to = toDate != null ? toDate : LocalDate.now();

    Page<LabourReturnResponse> response = labourReturnService.getReturnsByProject(projectId, from, to, pageable);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/summary")
  public ResponseEntity<ApiResponse<List<DeploymentSummary>>> getDeploymentSummary(
      @PathVariable UUID projectId) {
    log.info("GET /v1/projects/{}/labour-returns/summary - Computing deployment summary", projectId);
    List<DeploymentSummary> response = labourReturnService.getDeploymentSummary(projectId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
