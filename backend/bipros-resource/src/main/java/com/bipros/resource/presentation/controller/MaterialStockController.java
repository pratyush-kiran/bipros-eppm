package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.MaterialStockResponse;
import com.bipros.resource.application.service.MaterialStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PMS MasterData Screen 09b: Stock & Inventory Register. Serves the aggregated per-material
 * view at the project level plus a single-material detail endpoint for drill-down.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialStockController {

    private final MaterialStockService service;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/projects/{projectId}/stock-register")
    public ResponseEntity<ApiResponse<List<MaterialStockResponse>>> listByProject(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByProject(projectId)));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/projects/{projectId}/materials/{materialId}/stock")
    public ResponseEntity<ApiResponse<MaterialStockResponse>> getForMaterial(
            @PathVariable UUID projectId,
            @PathVariable UUID materialId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getForMaterial(projectId, materialId)));
    }
}
