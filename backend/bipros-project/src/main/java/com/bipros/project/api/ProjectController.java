package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.project.application.dto.ProjectResponse;
import com.bipros.project.application.dto.SetDataDateRequest;
import com.bipros.project.application.dto.UpdateProjectRequest;
import com.bipros.project.application.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects")
// Class-level RBAC is just an authentication gate: any signed-in user may HIT the endpoint.
// What they actually see/edit is enforced by service-layer ProjectAccessGuard (RLS) and the
// per-method @projectAccess.hasProjectPermission(...) checks (ABAC) further below.
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProjectResponse>>> listProjects(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        PagedResponse<ProjectResponse> response = projectService.listProjects(pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/archived")
    public ResponseEntity<ApiResponse<PagedResponse<ProjectResponse>>> listArchivedProjects(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "updatedAt") String sortBy,
        @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        PagedResponse<ProjectResponse> response = projectService.listArchivedProjects(pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProject(@PathVariable UUID id) {
        ProjectResponse response = projectService.getProject(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.CREATE')")
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#id, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
        @PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        ProjectResponse response = projectService.updateProject(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Advance (or rewind) the project's data date — the "as-of" cursor used by EVM, schedule
     * health, and progress reports. Separated from the omnibus PUT so a small UI control
     * (e.g. a date picker on the project header) can update just this field cleanly.
     */
    @PutMapping("/{id:[0-9a-fA-F-]{36}}/data-date")
    @PreAuthorize("@projectAccess.hasProjectPermission(#id, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<ProjectResponse>> setDataDate(
        @PathVariable UUID id, @Valid @RequestBody SetDataDateRequest request) {
        ProjectResponse response = projectService.setDataDate(id, request.dataDate());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Soft archive — flips {@code archived_at}. There is no hard-delete endpoint; this is the
     * only deletion verb the API exposes. Restore via {@code POST /v1/projects/{id}/restore}.
     */
    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#id, 'PROJECT.DELETE')")
    public ResponseEntity<Void> deleteProject(@PathVariable UUID id) {
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id:[0-9a-fA-F-]{36}}/restore")
    @PreAuthorize("@projectAccess.hasProjectPermission(#id, 'PROJECT.DELETE')")
    public ResponseEntity<ApiResponse<ProjectResponse>> restoreProject(@PathVariable UUID id) {
        ProjectResponse response = projectService.restoreProject(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/by-eps/{epsNodeId}")
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getProjectsByEps(@PathVariable UUID epsNodeId) {
        List<ProjectResponse> response = projectService.getProjectsByEps(epsNodeId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
