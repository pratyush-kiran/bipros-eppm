package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.CopyTemplatesRequest;
import com.bipros.risk.application.dto.CreateRiskRequest;
import com.bipros.risk.application.dto.CreateRiskResponseRequest;
import com.bipros.risk.application.dto.RiskActivityAssignmentDto;
import com.bipros.risk.application.dto.RiskAnalysisQuality;
import com.bipros.risk.application.dto.RiskResponseDto;
import com.bipros.risk.application.dto.RiskSummary;
import com.bipros.risk.application.dto.UpdateRiskRequest;
import com.bipros.risk.application.service.RiskService;
import com.bipros.risk.application.service.RiskTemplateService;
import com.bipros.risk.domain.model.RiskStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;
    private final RiskTemplateService riskTemplateService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.CREATE')")
    public ResponseEntity<ApiResponse<RiskSummary>> createRisk(
        @PathVariable UUID projectId,
        @Valid @RequestBody CreateRiskRequest request) {
        RiskSummary risk = riskService.createRisk(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(risk));
    }

    @GetMapping("/summary")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRisksSummary(
        @PathVariable UUID projectId,
        @RequestParam(required = false) RiskStatus status) {
        List<RiskSummary> risks = riskService.listRisks(projectId, status);
        BigDecimal exposure = riskService.calculateRiskExposure(projectId);
        Map<String, List<RiskSummary>> matrix = riskService.getRiskMatrix(projectId);
        long notAnalysed = risks.stream()
            .filter(r -> r.getAnalysisQuality() != null
                && r.getAnalysisQuality().level() != RiskAnalysisQuality.QualityLevel.WELL_ANALYSED)
            .count();
        Map<String, Object> body = Map.of(
            "totalRisks", risks.size(),
            "exposure", exposure,
            "byStatus", risks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    r -> r.getStatus() != null ? r.getStatus().name() : "UNKNOWN",
                    java.util.stream.Collectors.counting())),
            "matrix", matrix,
            "risksNotAnalysed", notAnalysed
        );
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/{riskId}/analysis-quality")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<RiskAnalysisQuality>> getAnalysisQuality(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId) {
        return ResponseEntity.ok(ApiResponse.ok(riskService.assessQuality(projectId, riskId)));
    }

    @GetMapping("/{riskId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<RiskSummary>> getRisk(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId) {
        RiskSummary risk = riskService.getRisk(projectId, riskId);
        return ResponseEntity.ok(ApiResponse.ok(risk));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskSummary>>> listRisks(
        @PathVariable UUID projectId,
        @RequestParam(required = false) RiskStatus status) {
        List<RiskSummary> risks = riskService.listRisks(projectId, status);
        return ResponseEntity.ok(ApiResponse.ok(risks));
    }

    @PutMapping("/{riskId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskSummary>> updateRisk(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @Valid @RequestBody UpdateRiskRequest request) {
        RiskSummary risk = riskService.updateRisk(projectId, riskId, request);
        return ResponseEntity.ok(ApiResponse.ok(risk));
    }

    @DeleteMapping("/{riskId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.DELETE')")
    public ResponseEntity<Void> deleteRisk(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId) {
        riskService.deleteRisk(projectId, riskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/matrix")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<Map<String, List<RiskSummary>>>> getRiskMatrix(
        @PathVariable UUID projectId) {
        Map<String, List<RiskSummary>> matrix = riskService.getRiskMatrix(projectId);
        return ResponseEntity.ok(ApiResponse.ok(matrix));
    }

    @GetMapping("/exposure")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<BigDecimal>> getRiskExposure(
        @PathVariable UUID projectId) {
        BigDecimal exposure = riskService.calculateRiskExposure(projectId);
        return ResponseEntity.ok(ApiResponse.ok(exposure));
    }

    // ── Activity Assignment ───────────────────────────────────────────────

    @PostMapping("/{riskId}/activities/{activityId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskActivityAssignmentDto>> addActivityToRisk(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @PathVariable UUID activityId) {
        RiskActivityAssignmentDto assignment = riskService.addActivityToRisk(projectId, riskId, activityId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(assignment));
    }

    @DeleteMapping("/{riskId}/activities/{activityId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<Void> removeActivityFromRisk(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @PathVariable UUID activityId) {
        riskService.removeActivityFromRisk(projectId, riskId, activityId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{riskId}/activities")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskActivityAssignmentDto>>> getAssignedActivities(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId) {
        List<RiskActivityAssignmentDto> activities = riskService.getAssignedActivities(projectId, riskId);
        return ResponseEntity.ok(ApiResponse.ok(activities));
    }

    // ── Risk Responses ────────────────────────────────────────────────────

    @PostMapping("/{riskId}/responses")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskResponseDto>> addResponse(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @Valid @RequestBody CreateRiskResponseRequest request) {
        RiskResponseDto response = riskService.addResponse(projectId, riskId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping("/{riskId}/responses")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskResponseDto>>> getResponses(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId) {
        List<RiskResponseDto> responses = riskService.getResponses(projectId, riskId);
        return ResponseEntity.ok(ApiResponse.ok(responses));
    }

    @PutMapping("/{riskId}/responses/{responseId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskResponseDto>> updateResponse(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @PathVariable UUID responseId,
        @Valid @RequestBody CreateRiskResponseRequest request) {
        RiskResponseDto response = riskService.updateResponse(projectId, riskId, responseId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{riskId}/responses/{responseId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.DELETE')")
    public ResponseEntity<Void> deleteResponse(
        @PathVariable UUID projectId,
        @PathVariable UUID riskId,
        @PathVariable UUID responseId) {
        riskService.deleteResponse(projectId, riskId, responseId);
        return ResponseEntity.noContent().build();
    }

    // ── Template Copy ─────────────────────────────────────────────────────

    @PostMapping("/copy-from-templates")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.CREATE')")
    public ResponseEntity<ApiResponse<List<RiskSummary>>> copyFromTemplates(
        @PathVariable UUID projectId,
        @Valid @RequestBody CopyTemplatesRequest request) {
        List<RiskSummary> created = riskTemplateService.copyToProject(projectId, request.templateIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
