package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateResourceRequest;
import com.bipros.resource.application.dto.EquipmentDetailsDto;
import com.bipros.resource.application.dto.ManpowerDto;
import com.bipros.resource.application.dto.MaterialDetailsDto;
import com.bipros.resource.application.dto.ResourceResponse;
import com.bipros.resource.application.service.EquipmentDetailsService;
import com.bipros.resource.application.service.ManpowerService;
import com.bipros.resource.application.service.MaterialDetailsService;
import com.bipros.resource.application.service.ResourceService;
import com.bipros.resource.domain.model.ResourceStatus;
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
@RequestMapping("/v1/resources")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceController {

  private final ResourceService resourceService;
  private final EquipmentDetailsService equipmentDetailsService;
  private final MaterialDetailsService materialDetailsService;
  private final ManpowerService manpowerService;

  // ─── Resource CRUD ───

  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> list() {
    log.info("GET /v1/resources - listing slim view");
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResources()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceResponse>> get(@PathVariable UUID id) {
    log.info("GET /v1/resources/{} - fetching with nested details", id);
    return ResponseEntity.ok(ApiResponse.ok(resourceService.getResource(id)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ResourceResponse>> create(
      @Valid @RequestBody CreateResourceRequest request) {
    log.info("POST /v1/resources - creating: code={}", request.code());
    ResourceResponse response = resourceService.createResource(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody CreateResourceRequest request) {
    log.info("PUT /v1/resources/{}", id);
    return ResponseEntity.ok(ApiResponse.ok(resourceService.updateResource(id, request)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    log.info("DELETE /v1/resources/{}", id);
    resourceService.deleteResource(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @DeleteMapping
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteAll() {
    log.info("DELETE /v1/resources");
    resourceService.deleteAllResources();
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ─── Filtered listings ───

  @GetMapping("/by-type/{typeCode}")
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> listByType(@PathVariable String typeCode) {
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResourcesByType(typeCode)));
  }

  @GetMapping("/by-role/{roleId}")
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> listByRole(@PathVariable UUID roleId) {
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResourcesByRole(roleId)));
  }

  @GetMapping("/roots")
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> listRoots() {
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResourceHierarchyRoots()));
  }

  @GetMapping("/by-parent/{parentId}")
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> listByParent(@PathVariable UUID parentId) {
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResourcesByParent(parentId)));
  }

  @GetMapping("/by-status/{status}")
  public ResponseEntity<ApiResponse<List<ResourceResponse>>> listByStatus(@PathVariable ResourceStatus status) {
    return ResponseEntity.ok(ApiResponse.ok(resourceService.listResourcesByStatus(status)));
  }

  // ─── Per-type detail endpoints ───

  @GetMapping("/{id}/equipment-details")
  public ResponseEntity<ApiResponse<EquipmentDetailsDto>> getEquipmentDetails(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(equipmentDetailsService.get(id)));
  }

  @PutMapping("/{id}/equipment-details")
  public ResponseEntity<ApiResponse<EquipmentDetailsDto>> updateEquipmentDetails(
      @PathVariable UUID id, @Valid @RequestBody EquipmentDetailsDto dto) {
    return ResponseEntity.ok(ApiResponse.ok(equipmentDetailsService.upsert(id, dto)));
  }

  @GetMapping("/{id}/material-details")
  public ResponseEntity<ApiResponse<MaterialDetailsDto>> getMaterialDetails(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(materialDetailsService.get(id)));
  }

  @PutMapping("/{id}/material-details")
  public ResponseEntity<ApiResponse<MaterialDetailsDto>> updateMaterialDetails(
      @PathVariable UUID id, @Valid @RequestBody MaterialDetailsDto dto) {
    return ResponseEntity.ok(ApiResponse.ok(materialDetailsService.upsert(id, dto)));
  }

  @GetMapping("/{id}/manpower")
  public ResponseEntity<ApiResponse<ManpowerDto>> getManpower(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(manpowerService.get(id)));
  }

  @PutMapping("/{id}/manpower")
  public ResponseEntity<ApiResponse<ManpowerDto>> updateManpower(
      @PathVariable UUID id, @Valid @RequestBody ManpowerDto dto) {
    return ResponseEntity.ok(ApiResponse.ok(manpowerService.upsertAll(id, dto)));
  }
}
