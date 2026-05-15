package com.bipros.gis.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.gis.application.dto.*;
import com.bipros.gis.application.service.WbsPolygonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/gis/polygons")
@RequiredArgsConstructor
public class WbsPolygonController {

    private final WbsPolygonService polygonService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<WbsPolygonResponse>> create(
        @PathVariable UUID projectId,
        @Valid @RequestBody WbsPolygonRequest request
    ) {
        WbsPolygonResponse response = polygonService.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<WbsPolygonResponse>>> getAll(
        @PathVariable UUID projectId
    ) {
        List<WbsPolygonResponse> response = polygonService.getByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{polygonId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<WbsPolygonResponse>> getById(
        @PathVariable UUID projectId,
        @PathVariable UUID polygonId
    ) {
        WbsPolygonResponse response = polygonService.getById(projectId, polygonId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/geojson")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<GeoJsonFeatureCollection>> getAsGeoJson(
        @PathVariable UUID projectId
    ) {
        GeoJsonFeatureCollection response = polygonService.getAsGeoJson(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{polygonId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<WbsPolygonResponse>> update(
        @PathVariable UUID projectId,
        @PathVariable UUID polygonId,
        @Valid @RequestBody WbsPolygonRequest request
    ) {
        WbsPolygonResponse response = polygonService.update(projectId, polygonId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{polygonId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable UUID projectId,
        @PathVariable UUID polygonId
    ) {
        polygonService.delete(projectId, polygonId);
        return ResponseEntity.noContent().build();
    }
}
