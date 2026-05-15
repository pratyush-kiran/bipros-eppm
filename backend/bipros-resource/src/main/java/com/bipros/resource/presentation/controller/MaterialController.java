package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateMaterialRequest;
import com.bipros.resource.application.dto.MaterialResponse;
import com.bipros.resource.application.service.MaterialService;
import com.bipros.resource.domain.model.MaterialCategory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService service;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/projects/{projectId}/materials")
    public ResponseEntity<ApiResponse<List<MaterialResponse>>> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(required = false) MaterialCategory category) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByProject(projectId, category)));
    }

    @PostMapping("/projects/{projectId}/materials")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateMaterialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping("/materials/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<MaterialResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @PutMapping("/materials/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateMaterialRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }

    @DeleteMapping("/materials/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
