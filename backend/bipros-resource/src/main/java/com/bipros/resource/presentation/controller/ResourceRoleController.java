package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ResourceRoleRequest;
import com.bipros.resource.application.dto.ResourceRoleResponse;
import com.bipros.resource.application.service.ResourceRoleService;
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
@RequestMapping("/v1/resource-roles")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceRoleController {

  private final ResourceRoleService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceRoleResponse>>> list() {
    log.info("GET /v1/resource-roles");
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/by-type/{typeId}")
  public ResponseEntity<ApiResponse<List<ResourceRoleResponse>>> listByType(@PathVariable UUID typeId) {
    log.info("GET /v1/resource-roles/by-type/{}", typeId);
    return ResponseEntity.ok(ApiResponse.ok(service.listByType(typeId)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceRoleResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'RESOURCE.CREATE')")
  public ResponseEntity<ApiResponse<ResourceRoleResponse>> create(
      @Valid @RequestBody ResourceRoleRequest request) {
    log.info("POST /v1/resource-roles - code={}", request.code());
    ResourceRoleResponse created = service.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceRoleResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody ResourceRoleRequest request) {
    log.info("PUT /v1/resource-roles/{}", id);
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    log.info("DELETE /v1/resource-roles/{}", id);
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
