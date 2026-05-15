package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.MonteCarloActivityStatDto;
import com.bipros.risk.application.dto.MonteCarloCashflowBucketDto;
import com.bipros.risk.application.dto.MonteCarloMilestoneStatDto;
import com.bipros.risk.application.dto.MonteCarloRiskContributionDto;
import com.bipros.risk.application.dto.MonteCarloRunRequest;
import com.bipros.risk.application.dto.MonteCarloSimulationDto;
import com.bipros.risk.application.service.MonteCarloService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/monte-carlo")
@RequiredArgsConstructor
public class MonteCarloController {

    private final MonteCarloService monteCarloService;

    @PostMapping("/run")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<MonteCarloSimulationDto>> runSimulation(
        @PathVariable UUID projectId,
        @RequestParam(required = false) Integer iterations,
        @RequestBody(required = false) @Valid MonteCarloRunRequest body) {

        MonteCarloRunRequest request = body != null ? body : new MonteCarloRunRequest();
        if (iterations != null) request.setIterations(iterations); // legacy query param
        MonteCarloSimulationDto result = monteCarloService.runSimulation(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @GetMapping("/latest")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<MonteCarloSimulationDto>> getLatestSimulation(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getLatestSimulation(projectId)));
    }

    @GetMapping("/{simulationId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<MonteCarloSimulationDto>> getSimulation(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getSimulation(simulationId)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloSimulationDto>>> listSimulations(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.listProjectSimulations(projectId)));
    }

    @GetMapping("/{simulationId}/activity-stats")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloActivityStatDto>>> getActivityStats(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getActivityStats(simulationId)));
    }

    @GetMapping("/{simulationId}/criticality")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloActivityStatDto>>> getCriticality(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getActivityStats(simulationId)));
    }

    @GetMapping("/{simulationId}/milestone-stats")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloMilestoneStatDto>>> getMilestoneStats(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getMilestoneStats(simulationId)));
    }

    @GetMapping("/{simulationId}/cashflow")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloCashflowBucketDto>>> getCashflow(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getCashflow(simulationId)));
    }

    @GetMapping("/{simulationId}/risk-contributions")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloRiskContributionDto>>> getRiskContributions(
        @PathVariable UUID projectId, @PathVariable UUID simulationId) {
        return ResponseEntity.ok(ApiResponse.ok(monteCarloService.getRiskContributions(simulationId)));
    }

    /**
     * Sensitivity tornado: ordered by |duration_sensitivity| descending. Returns the same
     * activity-stat payload as /criticality; UI picks the columns it needs.
     */
    @GetMapping("/{simulationId}/sensitivity-tornado")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<MonteCarloActivityStatDto>>> getTornado(
        @PathVariable UUID projectId, @PathVariable UUID simulationId,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "duration") String metric) {
        List<MonteCarloActivityStatDto> stats = monteCarloService.getActivityStats(simulationId);
        stats.sort((a, b) -> {
            double av = metric.equalsIgnoreCase("cost")
                ? (a.getCostSensitivity() != null ? Math.abs(a.getCostSensitivity()) : 0.0)
                : (a.getDurationSensitivity() != null ? Math.abs(a.getDurationSensitivity()) : 0.0);
            double bv = metric.equalsIgnoreCase("cost")
                ? (b.getCostSensitivity() != null ? Math.abs(b.getCostSensitivity()) : 0.0)
                : (b.getDurationSensitivity() != null ? Math.abs(b.getDurationSensitivity()) : 0.0);
            return Double.compare(bv, av);
        });
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }
}
