package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.RiskSummary;
import com.bipros.risk.application.service.RiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alias route for {@code GET /v1/projects/{id}/risks/matrix} at the more conventional location
 * {@code /v1/projects/{id}/risk-matrix}. Dashboards linking to a probability-impact matrix use
 * the hyphenated form; this controller serves them without duplicating the underlying query.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/risk-matrix")
@RequiredArgsConstructor
public class RiskMatrixController {

    private final RiskService riskService;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<Map<String, List<RiskSummary>>>> getRiskMatrix(
        @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(riskService.getRiskMatrix(projectId)));
    }
}
