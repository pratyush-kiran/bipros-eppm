package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ExpenseCategoryRequest;
import com.bipros.resource.application.dto.ExpenseCategoryResponse;
import com.bipros.resource.application.service.ExpenseCategoryService;
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

/**
 * Admin master-data CRUD for ad-hoc expense categories. Pure metadata. Per-row entries
 * (the actuals) live separately in {@code dbs.dbs_manual_expenses}.
 */
@RestController
@RequestMapping("/v1/expense-categories")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ExpenseCategoryController {

  private final ExpenseCategoryService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<ExpenseCategoryResponse>>> list(
      @RequestParam(defaultValue = "false") boolean activeOnly) {
    return ResponseEntity.ok(ApiResponse.ok(service.list(activeOnly)));
  }

  @GetMapping("/by-section/{section}")
  public ResponseEntity<ApiResponse<List<ExpenseCategoryResponse>>> listBySection(
      @PathVariable String section) {
    return ResponseEntity.ok(ApiResponse.ok(service.listBySection(section.toUpperCase())));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ExpenseCategoryResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'RESOURCE.CREATE')")
  public ResponseEntity<ApiResponse<ExpenseCategoryResponse>> create(
      @Valid @RequestBody ExpenseCategoryRequest request) {
    log.info("POST /v1/expense-categories code={}", request.code());
    ExpenseCategoryResponse created = service.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ExpenseCategoryResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody ExpenseCategoryRequest request) {
    log.info("PUT /v1/expense-categories/{}", id);
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    log.info("DELETE /v1/expense-categories/{}", id);
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
