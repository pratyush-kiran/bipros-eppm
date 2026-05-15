package com.bipros.document.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.RfiRegisterRequest;
import com.bipros.document.application.dto.RfiRegisterResponse;
import com.bipros.document.application.service.RfiRegisterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/rfis")
@RequiredArgsConstructor
public class RfiRegisterController {

    private final RfiRegisterService rfiService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<RfiRegisterResponse>> createRfi(
        @PathVariable UUID projectId,
        @Valid @RequestBody RfiRegisterRequest request) {
        RfiRegisterResponse response = rfiService.createRfi(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping("/{rfiId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<RfiRegisterResponse>> getRfi(
        @PathVariable UUID projectId,
        @PathVariable UUID rfiId) {
        RfiRegisterResponse response = rfiService.getRfi(projectId, rfiId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<RfiRegisterResponse>>> listRfis(
        @PathVariable UUID projectId) {
        List<RfiRegisterResponse> response = rfiService.listRfis(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{rfiId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<RfiRegisterResponse>> updateRfi(
        @PathVariable UUID projectId,
        @PathVariable UUID rfiId,
        @Valid @RequestBody RfiRegisterRequest request) {
        RfiRegisterResponse response = rfiService.updateRfi(projectId, rfiId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{rfiId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteRfi(
        @PathVariable UUID projectId,
        @PathVariable UUID rfiId) {
        rfiService.deleteRfi(projectId, rfiId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
