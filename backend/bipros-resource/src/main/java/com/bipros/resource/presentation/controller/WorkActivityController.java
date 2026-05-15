package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateWorkActivityRequest;
import com.bipros.resource.application.dto.WorkActivityResponse;
import com.bipros.resource.application.service.WorkActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/work-activities")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class WorkActivityController {

  private final WorkActivityService service;
  private final com.bipros.resource.application.service.ProductivityCoverageService coverageService;

  @GetMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
  public ResponseEntity<ApiResponse<List<WorkActivityResponse>>> list(
      @RequestParam(required = false) Boolean active) {
    log.info("GET /v1/work-activities - active={}", active);
    return ResponseEntity.ok(ApiResponse.ok(service.list(active)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
  public ResponseEntity<ApiResponse<WorkActivityResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  /**
   * Productivity coverage summary for a Work Activity: does it have manpower norms, equipment
   * norms, both, or none? Used by the Activity edit page to render the coverage chip under the
   * Work Activity picker, and by the DPR form to show the supervisor what will be measured.
   */
  @GetMapping("/{id}/productivity-coverage")
  public ResponseEntity<ApiResponse<com.bipros.resource.application.dto.ProductivityCoverageResponse>>
      productivityCoverage(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(coverageService.coverage(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<WorkActivityResponse>> create(
      @Valid @RequestBody CreateWorkActivityRequest request) {
    log.info("POST /v1/work-activities - name={}", request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<WorkActivityResponse>> update(
      @PathVariable UUID id,
      @Valid @RequestBody CreateWorkActivityRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @DeleteMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteAll() {
    service.deleteAll();
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
