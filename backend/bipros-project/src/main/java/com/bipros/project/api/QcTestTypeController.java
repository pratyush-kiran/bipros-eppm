package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateQcTestTypeRequest;
import com.bipros.project.application.dto.QcTestTypeResponse;
import com.bipros.project.application.dto.UpdateQcTestTypeRequest;
import com.bipros.project.application.service.QcTestTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/qc/test-types")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class QcTestTypeController {

    private final QcTestTypeService service;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.READ')")
    public ResponseEntity<ApiResponse<List<QcTestTypeResponse>>> list(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByProject(projectId)));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.CREATE')")
    public ResponseEntity<ApiResponse<QcTestTypeResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateQcTestTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.UPDATE')")
    public ResponseEntity<ApiResponse<QcTestTypeResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQcTestTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.UPDATE')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
