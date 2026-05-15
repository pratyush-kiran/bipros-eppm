package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.AddToPoolRequest;
import com.bipros.resource.application.dto.ProjectResourceResponse;
import com.bipros.resource.application.dto.ResourceResponse;
import com.bipros.resource.application.dto.UpdatePoolEntryRequest;
import com.bipros.resource.application.service.ProjectResourceService;
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
@RequestMapping("/v1/projects/{projectId}/resource-pool")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ProjectResourceController {

    private final ProjectResourceService projectResourceService;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResourceResponse>>> listPool(
            @PathVariable UUID projectId) {
        log.info("GET /v1/projects/{}/resource-pool - Listing pool", projectId);
        List<ProjectResourceResponse> response = projectResourceService.listPool(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/available")
    public ResponseEntity<ApiResponse<List<ResourceResponse>>> listAvailable(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) UUID roleId,
            @RequestParam(required = false) String q) {
        log.info("GET /v1/projects/{}/resource-pool/available - Listing available resources", projectId);
        List<ResourceResponse> response = projectResourceService.listAvailable(projectId, typeCode, roleId, q);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/by-role/{roleId}")
    public ResponseEntity<ApiResponse<List<ProjectResourceResponse>>> listPoolByRole(
            @PathVariable UUID projectId,
            @PathVariable UUID roleId) {
        log.info("GET /v1/projects/{}/resource-pool/by-role/{} - Listing pool by role", projectId, roleId);
        List<ProjectResourceResponse> response = projectResourceService.listPoolByRole(projectId, roleId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    @PostMapping
    public ResponseEntity<ApiResponse<List<ProjectResourceResponse>>> addToPool(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddToPoolRequest request) {
        log.info("POST /v1/projects/{}/resource-pool - Adding {} entries to pool", projectId, request.entries().size());
        List<ProjectResourceResponse> response = projectResourceService.addToPool(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectResourceResponse>> updatePoolEntry(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePoolEntryRequest request) {
        log.info("PUT /v1/projects/{}/resource-pool/{} - Updating pool entry", projectId, id);
        ProjectResourceResponse response = projectResourceService.updatePoolEntry(projectId, id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> removeFromPool(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        log.info("DELETE /v1/projects/{}/resource-pool/{} - Removing from pool", projectId, id);
        projectResourceService.removeFromPool(projectId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
