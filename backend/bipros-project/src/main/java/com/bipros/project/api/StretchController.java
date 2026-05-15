package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateStretchRequest;
import com.bipros.project.application.dto.StretchProgressResponse;
import com.bipros.project.application.dto.StretchResponse;
import com.bipros.project.application.dto.UpdateStretchRequest;
import com.bipros.project.application.service.StretchService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stretch Master controller (PMS MasterData Screen 06). Project-scoped list/create; top-level
 * {@code /{id}} URLs for the detail view consistent with other master-data resources.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class StretchController {

    private final StretchService stretchService;

    @GetMapping("/projects/{projectId}/stretches")
    public ResponseEntity<ApiResponse<List<StretchResponse>>> listByProject(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(stretchService.listByProject(projectId)));
    }

    @PostMapping("/projects/{projectId}/stretches")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<StretchResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateStretchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(stretchService.create(projectId, request)));
    }

    @GetMapping("/stretches/{id}")
    public ResponseEntity<ApiResponse<StretchResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(stretchService.get(id)));
    }

    /** Cost-weighted physical progress rollup across the BOQ items linked to this stretch. */
    @GetMapping("/stretches/{id}/progress")
    public ResponseEntity<ApiResponse<StretchProgressResponse>> progress(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(stretchService.progress(id)));
    }

    @PutMapping("/stretches/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<StretchResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStretchRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(stretchService.update(id, request)));
    }

    @DeleteMapping("/stretches/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        stretchService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Replace the set of BOQ items linked to a stretch. Body: {@code {"boqItemIds": [uuid,...]}}. */
    @PostMapping("/stretches/{id}/activities")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<StretchResponse>> assignActivities(
            @PathVariable UUID id,
            @RequestBody Map<String, List<UUID>> body) {
        List<UUID> boqItemIds = body.getOrDefault("boqItemIds", List.of());
        return ResponseEntity.ok(ApiResponse.ok(
            stretchService.assignActivities(id, boqItemIds)));
    }
}
