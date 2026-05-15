package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateProjectCategoryMasterRequest;
import com.bipros.project.application.dto.ProjectCategoryMasterResponse;
import com.bipros.project.application.dto.UpdateProjectCategoryMasterRequest;
import com.bipros.project.application.service.ProjectCategoryMasterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/project-categories")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProjectCategoryMasterController {

    private final ProjectCategoryMasterService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectCategoryMasterResponse>>> listCategories() {
        List<ProjectCategoryMasterResponse> response = service.listActive();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<ProjectCategoryMasterResponse>>> listAllCategories() {
        List<ProjectCategoryMasterResponse> response = service.listAll();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    public ResponseEntity<ApiResponse<ProjectCategoryMasterResponse>> getCategory(@PathVariable UUID id) {
        ProjectCategoryMasterResponse response = service.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.CREATE')")
    public ResponseEntity<ApiResponse<ProjectCategoryMasterResponse>> createCategory(
        @Valid @RequestBody CreateProjectCategoryMasterRequest request) {
        ProjectCategoryMasterResponse response = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<ProjectCategoryMasterResponse>> updateCategory(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateProjectCategoryMasterRequest request) {
        ProjectCategoryMasterResponse response = service.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'PROJECT.DELETE')")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
