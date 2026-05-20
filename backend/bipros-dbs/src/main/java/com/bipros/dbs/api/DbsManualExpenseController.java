package com.bipros.dbs.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.dbs.api.dto.DbsManualExpenseRequest;
import com.bipros.dbs.api.dto.DbsManualExpenseResponse;
import com.bipros.dbs.application.service.DbsManualExpenseService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-project DBS manual-expense ledger endpoints. List by date (or by activity),
 * CRUD, and PM-approve / reject for entries above the approval threshold.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/dbs/expenses")
@PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.READ')")
@RequiredArgsConstructor
@Slf4j
public class DbsManualExpenseController {

  private final DbsManualExpenseService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<DbsManualExpenseResponse>>> listForDate(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return ResponseEntity.ok(ApiResponse.ok(service.listForDate(projectId, date)));
  }

  @GetMapping("/by-activity/{activityId}")
  public ResponseEntity<ApiResponse<List<DbsManualExpenseResponse>>> listForActivity(
      @PathVariable UUID projectId, @PathVariable UUID activityId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listForActivity(activityId)));
  }

  /** Inbox for the PM — every PENDING expense on this project, newest first. */
  @GetMapping("/pending")
  public ResponseEntity<ApiResponse<List<DbsManualExpenseResponse>>> listPending(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listPending(projectId)));
  }

  /** Returns the resolved (project-override or global) approval threshold for hint UI. */
  @GetMapping("/approval-threshold")
  public ResponseEntity<ApiResponse<java.math.BigDecimal>> getThreshold(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.getApprovalThreshold(projectId)));
  }

  /** PM sets the per-project threshold override. Body: {@code { "threshold": 25000 }}. */
  @PutMapping("/approval-threshold")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<java.math.BigDecimal>> setThreshold(
      @PathVariable UUID projectId, @RequestBody ThresholdRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(service.setApprovalThreshold(projectId, body.threshold())));
  }

  /** Body shape for the threshold update — single field, validates @ service. */
  public record ThresholdRequest(java.math.BigDecimal threshold) {}

  @PostMapping
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<DbsManualExpenseResponse>> create(
      @PathVariable UUID projectId, @Valid @RequestBody DbsManualExpenseRequest request) {
    log.info("POST /v1/projects/{}/dbs/expenses date={} category={} amount={}",
        projectId, request.reportDate(), request.categoryId(), request.amount());
    DbsManualExpenseResponse saved = service.create(projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<DbsManualExpenseResponse>> update(
      @PathVariable UUID projectId, @PathVariable UUID id,
      @Valid @RequestBody DbsManualExpenseRequest request) {
    log.info("PUT /v1/projects/{}/dbs/expenses/{}", projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<DbsManualExpenseResponse>> approve(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.approve(id)));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<DbsManualExpenseResponse>> reject(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.reject(id)));
  }
}
