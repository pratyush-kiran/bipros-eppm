package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.LabourCategorySummary;
import com.bipros.resource.application.dto.LabourMasterDashboardSummary;
import com.bipros.resource.application.dto.ProjectLabourDeploymentRequest;
import com.bipros.resource.application.dto.ProjectLabourDeploymentResponse;
import com.bipros.resource.application.service.ProjectLabourDeploymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/labour-deployments")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ProjectLabourDeploymentController {

    private final ProjectLabourDeploymentService service;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<List<ProjectLabourDeploymentResponse>>> list(
            @PathVariable UUID projectId) {
        log.info("GET /v1/projects/{}/labour-deployments", projectId);
        return ResponseEntity.ok(ApiResponse.ok(service.listForProject(projectId)));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<LabourMasterDashboardSummary>> dashboard(
            @PathVariable UUID projectId) {
        log.info("GET /v1/projects/{}/labour-deployments/dashboard", projectId);
        return ResponseEntity.ok(ApiResponse.ok(service.dashboard(projectId)));
    }

    @GetMapping("/by-category")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<List<LabourCategorySummary>>> byCategory(
            @PathVariable UUID projectId) {
        log.info("GET /v1/projects/{}/labour-deployments/by-category", projectId);
        return ResponseEntity.ok(ApiResponse.ok(service.byCategory(projectId)));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<ProjectLabourDeploymentResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectLabourDeploymentRequest req) {
        log.info("POST /v1/projects/{}/labour-deployments designation={}", projectId, req.designationId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, req)));
    }

    @PutMapping("/{deploymentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<ProjectLabourDeploymentResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID deploymentId,
            @Valid @RequestBody ProjectLabourDeploymentRequest req) {
        log.info("PUT /v1/projects/{}/labour-deployments/{} - Updating deployment", projectId, deploymentId);
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, deploymentId, req)));
    }

    @DeleteMapping("/{deploymentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID deploymentId) {
        log.info("DELETE /v1/projects/{}/labour-deployments/{} - Deleting deployment", projectId, deploymentId);
        service.delete(projectId, deploymentId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
