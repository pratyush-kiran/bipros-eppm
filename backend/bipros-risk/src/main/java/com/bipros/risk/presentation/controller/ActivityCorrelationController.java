package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.ActivityCorrelationDto;
import com.bipros.risk.application.service.ActivityCorrelationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/activity-correlations")
@RequiredArgsConstructor
public class ActivityCorrelationController {

    private final ActivityCorrelationService service;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<ActivityCorrelationDto>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listForProject(projectId)));
    }

    /**
     * Upsert a correlation. Pair order doesn't matter — the service canonicalises it.
     * Send coefficient in (-0.99, 0.99). Pass 0 to effectively disable (or DELETE to remove).
     */
    @PutMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<ActivityCorrelationDto>> upsert(
        @PathVariable UUID projectId,
        @Valid @RequestBody ActivityCorrelationDto body) {
        return ResponseEntity.ok(ApiResponse.ok(service.upsert(projectId, body)));
    }

    @DeleteMapping("/{activityAId}/{activityBId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
        @PathVariable UUID projectId,
        @PathVariable UUID activityAId,
        @PathVariable UUID activityBId) {
        service.delete(projectId, activityAId, activityBId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
