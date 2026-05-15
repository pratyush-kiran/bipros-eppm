package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ManpowerCategoryMasterRequest;
import com.bipros.resource.application.dto.ManpowerCategoryMasterResponse;
import com.bipros.resource.application.service.ManpowerCategoryMasterService;
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

@RestController
@RequestMapping("/v1/admin/manpower-categories")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
@RequiredArgsConstructor
@Slf4j
public class ManpowerCategoryMasterController {

  private final ManpowerCategoryMasterService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<ManpowerCategoryMasterResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/top-level")
  public ResponseEntity<ApiResponse<List<ManpowerCategoryMasterResponse>>> listTopLevel() {
    return ResponseEntity.ok(ApiResponse.ok(service.listTopLevel()));
  }

  @GetMapping("/by-parent/{parentId}")
  public ResponseEntity<ApiResponse<List<ManpowerCategoryMasterResponse>>> listByParent(
      @PathVariable UUID parentId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listByParent(parentId)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ManpowerCategoryMasterResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<ManpowerCategoryMasterResponse>> create(
      @Valid @RequestBody ManpowerCategoryMasterRequest request) {
    log.info("POST /v1/admin/manpower-categories - code={}", request.code());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(request)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<ManpowerCategoryMasterResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody ManpowerCategoryMasterRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
