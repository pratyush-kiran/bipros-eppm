package com.bipros.reporting.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.application.dto.DashboardConfigDto;
import com.bipros.reporting.application.dto.CreateKpiDefinitionRequest;
import com.bipros.reporting.application.dto.KpiDefinitionDto;
import com.bipros.reporting.application.dto.KpiSnapshotDto;
import com.bipros.reporting.application.service.DashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/dashboards")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** List all configured dashboards. Serves {@code GET /v1/dashboards} so consumers can
     * discover tier ids before drilling into {@code /v1/dashboards/{tier}}. */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<DashboardConfigDto>>> listDashboards() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.listDashboards()));
    }

    @GetMapping("/{tier}")
    @PreAuthorize("hasPermission(null, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<DashboardConfigDto>> getDashboardByTier(
            @PathVariable String tier) {
        DashboardConfigDto response = dashboardService.getDashboardByTier(tier);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{tier}/kpis")
    @PreAuthorize("hasPermission(null, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<KpiSnapshotDto>>> getDashboardKpis(
            @PathVariable String tier,
            @RequestParam(required = false) UUID projectId) {
        List<KpiSnapshotDto> response = dashboardService.getKpisByTierAndProject(tier, projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/kpi-definitions")
    @PreAuthorize("hasPermission(null, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<KpiDefinitionDto>>> getKpiDefinitions() {
        List<KpiDefinitionDto> response = dashboardService.getAllKpiDefinitions();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/kpi-definitions")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<KpiDefinitionDto>> createKpiDefinition(
            @Valid @RequestBody CreateKpiDefinitionRequest request) {
        KpiDefinitionDto response = dashboardService.createKpiDefinition(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/projects/{projectId}/kpi-snapshots")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<KpiSnapshotDto>>> getProjectKpiSnapshots(
            @PathVariable UUID projectId) {
        List<KpiSnapshotDto> response = dashboardService.getProjectKpiSnapshots(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/projects/{projectId}/kpi-snapshots/calculate")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<List<KpiSnapshotDto>>> calculateProjectKpis(
            @PathVariable UUID projectId) {
        List<KpiSnapshotDto> response = dashboardService.calculateProjectKpis(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
