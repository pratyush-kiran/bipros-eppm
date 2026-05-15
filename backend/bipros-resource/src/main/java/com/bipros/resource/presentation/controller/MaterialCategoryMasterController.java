package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.MaterialCategoryMasterRequest;
import com.bipros.resource.application.dto.MaterialCategoryMasterResponse;
import com.bipros.resource.application.service.MaterialCategoryMasterService;
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
@RequestMapping("/v1/material-category-master")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
@RequiredArgsConstructor
@Slf4j
public class MaterialCategoryMasterController {

  private final MaterialCategoryMasterService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<MaterialCategoryMasterResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<MaterialCategoryMasterResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<MaterialCategoryMasterResponse>> create(
      @Valid @RequestBody MaterialCategoryMasterRequest request) {
    log.info("POST /v1/material-category-master - code={}", request.code());
    MaterialCategoryMasterResponse created = service.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<MaterialCategoryMasterResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody MaterialCategoryMasterRequest request) {
    log.info("PUT /v1/material-category-master/{}", id);
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    log.info("DELETE /v1/material-category-master/{}", id);
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
