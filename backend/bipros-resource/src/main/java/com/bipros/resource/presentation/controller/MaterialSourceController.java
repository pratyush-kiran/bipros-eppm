package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateMaterialSourceRequest;
import com.bipros.resource.application.dto.MaterialSourceResponse;
import com.bipros.resource.application.service.MaterialSourceService;
import com.bipros.resource.domain.model.MaterialSourceType;
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

/**
 * Material Source Master (PMS MasterData Screen 08). Serves borrow areas, quarries, bitumen
 * depots and cement sources from the same endpoint — filter by {@code sourceType} for a single
 * tab.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialSourceController {

    private final MaterialSourceService service;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/projects/{projectId}/material-sources")
    public ResponseEntity<ApiResponse<List<MaterialSourceResponse>>> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(required = false) MaterialSourceType sourceType) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByProject(projectId, sourceType)));
    }

    @PostMapping("/projects/{projectId}/material-sources")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialSourceResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateMaterialSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping("/material-sources/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<MaterialSourceResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @PutMapping("/material-sources/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialSourceResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateMaterialSourceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }

    @DeleteMapping("/material-sources/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
