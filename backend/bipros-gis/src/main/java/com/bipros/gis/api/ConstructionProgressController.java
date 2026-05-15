package com.bipros.gis.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.gis.application.dto.ConstructionProgressRequest;
import com.bipros.gis.application.dto.ConstructionProgressResponse;
import com.bipros.gis.application.dto.ProgressVarianceResponse;
import com.bipros.gis.application.service.ConstructionProgressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/gis/progress-snapshots")
@RequiredArgsConstructor
public class ConstructionProgressController {

    private final ConstructionProgressService progressService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<ConstructionProgressResponse>> create(
        @PathVariable UUID projectId,
        @Valid @RequestBody ConstructionProgressRequest request
    ) {
        ConstructionProgressResponse response = progressService.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<ConstructionProgressResponse>>> getAll(
        @PathVariable UUID projectId,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        List<ConstructionProgressResponse> response;
        if (from != null && to != null) {
            response = progressService.getByProjectAndDateRange(projectId, from, to);
        } else {
            response = progressService.getByProject(projectId);
        }
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{snapshotId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<ConstructionProgressResponse>> getById(
        @PathVariable UUID projectId,
        @PathVariable UUID snapshotId
    ) {
        ConstructionProgressResponse response = progressService.getById(projectId, snapshotId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/wbs/{polygonId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<ConstructionProgressResponse>>> getByWbsPolygon(
        @PathVariable UUID projectId,
        @PathVariable UUID polygonId
    ) {
        List<ConstructionProgressResponse> response = progressService.getByWbsPolygon(projectId, polygonId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{snapshotId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<ConstructionProgressResponse>> update(
        @PathVariable UUID projectId,
        @PathVariable UUID snapshotId,
        @Valid @RequestBody ConstructionProgressRequest request
    ) {
        ConstructionProgressResponse response = progressService.update(projectId, snapshotId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{snapshotId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable UUID projectId,
        @PathVariable UUID snapshotId
    ) {
        progressService.delete(projectId, snapshotId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/variance")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<ProgressVarianceResponse>>> getProgressVariance(
        @PathVariable UUID projectId
    ) {
        List<ProgressVarianceResponse> response = progressService.getProgressVariance(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
