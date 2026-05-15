package com.bipros.resource.presentation.controller.role;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.role.EquipmentRoleVariantRequest;
import com.bipros.resource.application.dto.role.EquipmentRoleVariantResponse;
import com.bipros.resource.application.dto.role.ManpowerRoleRateRequest;
import com.bipros.resource.application.dto.role.ManpowerRoleRateResponse;
import com.bipros.resource.application.dto.role.MaterialRoleVariantRequest;
import com.bipros.resource.application.dto.role.MaterialRoleVariantResponse;
import com.bipros.resource.application.dto.role.ProjectRoleRateOverrideRequest;
import com.bipros.resource.application.dto.role.ProjectRoleRateOverrideResponse;
import com.bipros.resource.application.dto.role.RoleWithVariantsRequest;
import com.bipros.resource.application.dto.role.RoleWithVariantsResponse;
import com.bipros.resource.application.service.role.RoleRateService;
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
 * CRUD for the role-owned rate book: manpower rates (skill+grade), equipment variants
 * (make+model) and material variants (spec/grade). Replaces the legacy {@code rate-master}
 * controllers, which remain available but deprecated.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class RoleRateController {

  private final RoleRateService service;

  // ===== Manpower =====

  @GetMapping("/roles/{roleId}/manpower-rates")
  public ResponseEntity<ApiResponse<List<ManpowerRoleRateResponse>>> listManpowerForRole(
      @PathVariable UUID roleId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listManpowerForRole(roleId)));
  }

  @PostMapping("/roles/{roleId}/manpower-rates")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ManpowerRoleRateResponse>> createManpowerRate(
      @PathVariable UUID roleId, @Valid @RequestBody ManpowerRoleRateRequest req) {
    log.info("POST /v1/roles/{}/manpower-rates", roleId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createManpowerRate(roleId, req)));
  }

  @PutMapping("/manpower-rates/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ManpowerRoleRateResponse>> updateManpowerRate(
      @PathVariable UUID id, @Valid @RequestBody ManpowerRoleRateRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.updateManpowerRate(id, req)));
  }

  @DeleteMapping("/manpower-rates/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteManpowerRate(@PathVariable UUID id) {
    service.deleteManpowerRate(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ===== Equipment =====

  @GetMapping("/roles/{roleId}/equipment-variants")
  public ResponseEntity<ApiResponse<List<EquipmentRoleVariantResponse>>> listEquipmentForRole(
      @PathVariable UUID roleId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listEquipmentForRole(roleId)));
  }

  @PostMapping("/roles/{roleId}/equipment-variants")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<EquipmentRoleVariantResponse>> createEquipmentVariant(
      @PathVariable UUID roleId, @Valid @RequestBody EquipmentRoleVariantRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createEquipmentVariant(roleId, req)));
  }

  @PutMapping("/equipment-variants/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<EquipmentRoleVariantResponse>> updateEquipmentVariant(
      @PathVariable UUID id, @Valid @RequestBody EquipmentRoleVariantRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.updateEquipmentVariant(id, req)));
  }

  @DeleteMapping("/equipment-variants/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteEquipmentVariant(@PathVariable UUID id) {
    service.deleteEquipmentVariant(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ===== Material =====

  @GetMapping("/roles/{roleId}/material-variants")
  public ResponseEntity<ApiResponse<List<MaterialRoleVariantResponse>>> listMaterialForRole(
      @PathVariable UUID roleId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listMaterialForRole(roleId)));
  }

  @PostMapping("/roles/{roleId}/material-variants")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<MaterialRoleVariantResponse>> createMaterialVariant(
      @PathVariable UUID roleId, @Valid @RequestBody MaterialRoleVariantRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createMaterialVariant(roleId, req)));
  }

  @PutMapping("/material-variants/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<MaterialRoleVariantResponse>> updateMaterialVariant(
      @PathVariable UUID id, @Valid @RequestBody MaterialRoleVariantRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.updateMaterialVariant(id, req)));
  }

  @DeleteMapping("/material-variants/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteMaterialVariant(@PathVariable UUID id) {
    service.deleteMaterialVariant(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ===== Project Overrides =====

  @GetMapping("/projects/{projectId}/role-rate-overrides")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  public ResponseEntity<ApiResponse<List<ProjectRoleRateOverrideResponse>>> listOverrides(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listOverridesForProject(projectId)));
  }

  @PostMapping("/projects/{projectId}/role-rate-overrides")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ProjectRoleRateOverrideResponse>> createOverride(
      @PathVariable UUID projectId, @Valid @RequestBody ProjectRoleRateOverrideRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createOverride(projectId, req)));
  }

  @DeleteMapping("/role-rate-overrides/manpower/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteManpowerOverride(@PathVariable UUID id) {
    service.deleteManpowerOverride(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @DeleteMapping("/role-rate-overrides/equipment/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteEquipmentOverride(@PathVariable UUID id) {
    service.deleteEquipmentOverride(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @DeleteMapping("/role-rate-overrides/material/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteMaterialOverride(@PathVariable UUID id) {
    service.deleteMaterialOverride(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ===== Combined Role + Variants (one-shot save) =====

  @GetMapping("/resource-roles/{roleId}/with-variants")
  public ResponseEntity<ApiResponse<RoleWithVariantsResponse>> getWithVariants(
      @PathVariable UUID roleId) {
    return ResponseEntity.ok(ApiResponse.ok(service.getRoleWithVariants(roleId)));
  }

  @PostMapping("/resource-roles/with-variants")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<RoleWithVariantsResponse>> createWithVariants(
      @Valid @RequestBody RoleWithVariantsRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createRoleWithVariants(req)));
  }

  @PutMapping("/resource-roles/{roleId}/with-variants")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<RoleWithVariantsResponse>> updateWithVariants(
      @PathVariable UUID roleId, @Valid @RequestBody RoleWithVariantsRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.updateRoleWithVariants(roleId, req)));
  }
}
