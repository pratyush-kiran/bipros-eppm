package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CreateSafetyRecordRequest;
import com.bipros.siteops.application.dto.SafetyRecordResponse;
import com.bipros.siteops.application.dto.UpdateSafetyRecordRequest;
import com.bipros.siteops.application.service.SafetyService;
import com.bipros.siteops.domain.model.SafetyKind;
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
@RequestMapping("/v1/projects/{projectId}/safety")
public class SafetyController {

    private final SafetyService service;

    // Create gate accepts either SAFETY.CREATE (general) OR SAFETY.INCIDENT_LOG. The
    // narrower INCIDENT_LOG-only role is meant for site personnel who can log
    // observations/near-misses/incidents without holding the full CREATE permission.
    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SAFETY.CREATE') "
            + "or @projectAccess.hasProjectPermission(#projectId, 'SAFETY.INCIDENT_LOG')")
    public ResponseEntity<ApiResponse<SafetyRecordResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateSafetyRecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SAFETY.READ')")
    public ResponseEntity<ApiResponse<List<SafetyRecordResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) SafetyKind kind) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, kind)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SAFETY.READ')")
    public ResponseEntity<ApiResponse<SafetyRecordResponse>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SAFETY.UPDATE')")
    public ResponseEntity<ApiResponse<SafetyRecordResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSafetyRecordRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
    }
}
