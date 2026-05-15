package com.bipros.gis.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.gis.application.dto.GisLayerRequest;
import com.bipros.gis.application.dto.GisLayerResponse;
import com.bipros.gis.application.service.GisLayerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/gis/layers")
@RequiredArgsConstructor
public class GisLayerController {

    private final GisLayerService layerService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<GisLayerResponse>> create(
        @PathVariable UUID projectId,
        @Valid @RequestBody GisLayerRequest request
    ) {
        GisLayerResponse response = layerService.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<GisLayerResponse>>> getAll(
        @PathVariable UUID projectId
    ) {
        List<GisLayerResponse> response = layerService.getByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{layerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<GisLayerResponse>> getById(
        @PathVariable UUID projectId,
        @PathVariable UUID layerId
    ) {
        GisLayerResponse response = layerService.getById(projectId, layerId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{layerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<GisLayerResponse>> update(
        @PathVariable UUID projectId,
        @PathVariable UUID layerId,
        @Valid @RequestBody GisLayerRequest request
    ) {
        GisLayerResponse response = layerService.update(projectId, layerId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{layerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable UUID projectId,
        @PathVariable UUID layerId
    ) {
        layerService.delete(projectId, layerId);
        return ResponseEntity.noContent().build();
    }
}
