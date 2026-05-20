package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ActivityExpenseBudgetRequest;
import com.bipros.resource.application.dto.ActivityExpenseBudgetResponse;
import com.bipros.resource.application.service.ActivityExpenseBudgetService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-activity ad-hoc expense planned budgets. List/upsert/delete only — upsert handles
 * both create (new (activity, category) pair) and update (existing pair).
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/activities/{activityId}/expense-budgets")
@PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.READ')")
@RequiredArgsConstructor
@Slf4j
public class ActivityExpenseBudgetController {

  private final ActivityExpenseBudgetService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<ActivityExpenseBudgetResponse>>> list(
      @PathVariable UUID projectId, @PathVariable UUID activityId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listForActivity(activityId)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityExpenseBudgetResponse>> create(
      @PathVariable UUID projectId, @PathVariable UUID activityId,
      @Valid @RequestBody ActivityExpenseBudgetRequest request) {
    log.info("POST /v1/projects/{}/activities/{}/expense-budgets categoryId={}",
        projectId, activityId, request.categoryId());
    ActivityExpenseBudgetResponse saved = service.upsert(projectId, activityId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
  }

  @PutMapping
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityExpenseBudgetResponse>> upsert(
      @PathVariable UUID projectId, @PathVariable UUID activityId,
      @Valid @RequestBody ActivityExpenseBudgetRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.upsert(projectId, activityId, request)));
  }

  @DeleteMapping("/{budgetId}")
  @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId, @PathVariable UUID activityId,
      @PathVariable UUID budgetId) {
    log.info("DELETE expense-budget projectId={} activityId={} id={}", projectId, activityId, budgetId);
    service.delete(activityId, budgetId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
