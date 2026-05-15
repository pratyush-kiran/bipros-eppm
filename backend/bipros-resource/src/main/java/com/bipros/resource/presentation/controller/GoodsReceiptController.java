package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateGoodsReceiptRequest;
import com.bipros.resource.application.dto.GoodsReceiptResponse;
import com.bipros.resource.application.service.GoodsReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService service;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    @GetMapping("/projects/{projectId}/grns")
    public ResponseEntity<ApiResponse<List<GoodsReceiptResponse>>> listByProject(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByProject(projectId)));
    }

    @PostMapping("/projects/{projectId}/grns")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<GoodsReceiptResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateGoodsReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping("/grns/{id}")
    @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<GoodsReceiptResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @GetMapping("/materials/{materialId}/grns")
    @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<List<GoodsReceiptResponse>>> listByMaterial(
            @PathVariable UUID materialId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByMaterial(materialId)));
    }
}
