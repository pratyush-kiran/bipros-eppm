package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CloseNcrRequest;
import com.bipros.siteops.application.dto.CreateNcrRequest;
import com.bipros.siteops.application.dto.NcrResponse;
import com.bipros.siteops.application.dto.RejectNcrRequest;
import com.bipros.siteops.application.dto.UpdateNcrRequest;
import com.bipros.siteops.application.service.NcrService;
import com.bipros.siteops.domain.model.NcrStatus;
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
@RequestMapping("/v1/projects/{projectId}/ncrs")
public class NcrController {

    private final NcrService service;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.CREATE')")
    public ResponseEntity<ApiResponse<NcrResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateNcrRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.READ')")
    public ResponseEntity<ApiResponse<List<NcrResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) NcrStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, status)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.READ')")
    public ResponseEntity<ApiResponse<NcrResponse>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.UPDATE')")
    public ResponseEntity<ApiResponse<NcrResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateNcrRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
    }

    @PostMapping("/{id}/approve-closure")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.APPROVE')")
    public ResponseEntity<ApiResponse<NcrResponse>> approveClosure(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody CloseNcrRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.approveClosure(projectId, id, request)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'NCR.APPROVE')")
    public ResponseEntity<ApiResponse<NcrResponse>> reject(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) RejectNcrRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.reject(projectId, id, request)));
    }
}
