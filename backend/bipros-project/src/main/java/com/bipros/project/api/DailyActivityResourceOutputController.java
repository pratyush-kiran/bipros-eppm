package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateDailyActivityResourceOutputRequest;
import com.bipros.project.application.dto.DailyActivityResourceOutputResponse;
import com.bipros.project.application.service.DailyActivityResourceOutputService;
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
@RequestMapping("/v1/projects/{projectId}/activity-resource-outputs")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class DailyActivityResourceOutputController {

  private final DailyActivityResourceOutputService service;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.CREATE')")
  public ResponseEntity<ApiResponse<DailyActivityResourceOutputResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateDailyActivityResourceOutputRequest request) {
    log.info("POST /v1/projects/{}/activity-resource-outputs - date={}, activity={}, resource={}",
        projectId, request.outputDate(), request.activityId(), request.resourceId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(projectId, request)));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.CREATE')")
  public ResponseEntity<ApiResponse<List<DailyActivityResourceOutputResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateDailyActivityResourceOutputRequest> requests) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createBulk(projectId, requests)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<DailyActivityResourceOutputResponse>>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID activityId,
      @RequestParam(required = false) UUID resourceId) {
    return ResponseEntity.ok(ApiResponse.ok(
        service.list(projectId, from, to, activityId, resourceId)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<DailyActivityResourceOutputResponse>> get(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId, @PathVariable UUID id) {
    service.delete(projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
