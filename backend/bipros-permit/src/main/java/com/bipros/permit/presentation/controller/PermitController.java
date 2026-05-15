package com.bipros.permit.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.permit.application.dto.CreatePermitRequest;
import com.bipros.permit.application.dto.PermitDetailResponse;
import com.bipros.permit.application.dto.PermitSummary;
import com.bipros.permit.application.dto.UpdatePermitRequest;
import com.bipros.permit.application.service.PermitService;
import com.bipros.permit.application.service.PermitVerifyService;
import com.bipros.permit.domain.model.Permit;
import com.bipros.permit.domain.model.PermitStatus;
import com.bipros.permit.domain.model.RiskLevel;
import com.bipros.permit.domain.repository.PermitRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PermitController {

    private final PermitService permitService;
    private final PermitVerifyService verifyService;
    private final PermitRepository permitRepository;

    // ── Cross-project list & detail (server filters by access) ──────────────

    @GetMapping("/v1/permits")
    @PreAuthorize("hasPermission(null, 'PERMIT.READ')")
    public ResponseEntity<ApiResponse<Page<PermitSummary>>> listAcrossProjects(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) PermitStatus status,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(
                permitService.listPermits(projectId, status, typeId, riskLevel, pageable)));
    }

    @GetMapping("/v1/permits/{id}")
    @PreAuthorize("hasPermission(null, 'PERMIT.READ')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> getDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(permitService.getPermit(id)));
    }

    @GetMapping(value = "/v1/permits/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasPermission(null, 'PERMIT.READ')")
    public ResponseEntity<byte[]> qrPng(@PathVariable UUID id, @RequestParam(defaultValue = "240") int size) {
        // Authenticated callers only — server-side filter ensures permit-access via getPermit's read gate.
        permitService.getPermit(id);
        Permit p = permitRepository.findById(id).orElseThrow();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(verifyService.renderPermitQrPng(p, Math.max(120, Math.min(800, size))));
    }

    // ── Project-scoped mutations ────────────────────────────────────────────

    @PostMapping("/v1/projects/{projectId}/permits")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PERMIT.CREATE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreatePermitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(permitService.createPermit(projectId, request)));
    }

    @PutMapping("/v1/projects/{projectId}/permits/{permitId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PERMIT.CREATE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID permitId,
            @Valid @RequestBody UpdatePermitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(permitService.updatePermit(projectId, permitId, request)));
    }

    @PostMapping("/v1/projects/{projectId}/permits/{permitId}/submit")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PERMIT.CREATE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> submit(
            @PathVariable UUID projectId,
            @PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(permitService.submit(projectId, permitId)));
    }

    @DeleteMapping("/v1/projects/{projectId}/permits/{permitId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PERMIT.CREATE')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID permitId) {
        permitService.deletePermit(projectId, permitId);
        return ResponseEntity.noContent().build();
    }
}
