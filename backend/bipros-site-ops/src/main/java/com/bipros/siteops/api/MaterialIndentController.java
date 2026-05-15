package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CreateMaterialIndentRequest;
import com.bipros.siteops.application.dto.IndentDecisionRequest;
import com.bipros.siteops.application.dto.MaterialIndentResponse;
import com.bipros.siteops.application.dto.UpdateMaterialIndentRequest;
import com.bipros.siteops.application.service.MaterialIndentService;
import com.bipros.siteops.domain.model.IndentStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/material-indents")
public class MaterialIndentController {

    private final MaterialIndentService service;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.CREATE')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateMaterialIndentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.READ')")
    public ResponseEntity<ApiResponse<List<MaterialIndentResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) IndentStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, status)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.READ')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMaterialIndentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.UPDATE')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> submit(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.submit(projectId, id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.APPROVE')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> approve(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) IndentDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.approve(projectId, id, request)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROCUREMENT_REQUEST.APPROVE')")
    public ResponseEntity<ApiResponse<MaterialIndentResponse>> reject(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) IndentDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.reject(projectId, id, request)));
    }
}
