package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CreateWorkfrontRequest;
import com.bipros.siteops.application.dto.UpdateWorkfrontRequest;
import com.bipros.siteops.application.dto.WorkfrontResponse;
import com.bipros.siteops.application.service.WorkfrontService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/workfronts")
public class WorkfrontController {

    private final WorkfrontService workfrontService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'WORKFRONT.CREATE')")
    public ResponseEntity<ApiResponse<WorkfrontResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateWorkfrontRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(workfrontService.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'WORKFRONT.READ')")
    public ResponseEntity<ApiResponse<List<WorkfrontResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(workfrontService.listByProject(projectId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'WORKFRONT.READ')")
    public ResponseEntity<ApiResponse<WorkfrontResponse>> detail(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(workfrontService.getById(projectId, id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'WORKFRONT.UPDATE')")
    public ResponseEntity<ApiResponse<WorkfrontResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkfrontRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workfrontService.update(projectId, id, request)));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'WORKFRONT.RELEASE')")
    public ResponseEntity<ApiResponse<WorkfrontResponse>> release(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(workfrontService.release(projectId, id)));
    }
}
