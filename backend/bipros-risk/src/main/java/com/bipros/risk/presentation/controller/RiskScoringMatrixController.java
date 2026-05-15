package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.risk.application.dto.RiskScoringConfigDto;
import com.bipros.risk.application.dto.RiskScoringMatrixDto;
import com.bipros.risk.application.service.RiskScoringMatrixService;
import com.bipros.risk.domain.model.RiskScoringConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/risk-scoring")
@RequiredArgsConstructor
@Validated
public class RiskScoringMatrixController {

    private final RiskScoringMatrixService matrixService;

    @GetMapping("/matrix")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskScoringMatrixDto>>> getMatrix(
        @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(matrixService.getMatrix(projectId)));
    }

    @PutMapping("/matrix")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<List<RiskScoringMatrixDto>>> updateMatrix(
        @PathVariable UUID projectId,
        @RequestBody @Valid @NotEmpty List<RiskScoringMatrixDto> cells) {
        return ResponseEntity.ok(ApiResponse.ok(matrixService.updateMatrix(projectId, cells)));
    }

    @GetMapping("/config")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<RiskScoringConfigDto>> getConfig(
        @PathVariable UUID projectId) {
        RiskScoringConfig config = matrixService.getConfig(projectId);
        return ResponseEntity.ok(ApiResponse.ok(toDto(config)));
    }

    @PutMapping("/config")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskScoringConfigDto>> updateConfig(
        @PathVariable UUID projectId,
        @RequestBody Map<String, String> body) {
        if (body == null || body.get("scoringMethod") == null
            || body.get("scoringMethod").isBlank()) {
            throw new BusinessRuleException("SCORING_METHOD_REQUIRED",
                "Request body must include 'scoringMethod'");
        }
        RiskScoringConfig.ScoringMethod method;
        try {
            method = RiskScoringConfig.ScoringMethod.valueOf(
                body.get("scoringMethod").trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException("INVALID_SCORING_METHOD",
                "Unknown scoringMethod '" + body.get("scoringMethod") + "'");
        }
        RiskScoringConfig config = matrixService.updateConfig(projectId, method);
        return ResponseEntity.ok(ApiResponse.ok(toDto(config)));
    }

    private RiskScoringConfigDto toDto(RiskScoringConfig config) {
        return RiskScoringConfigDto.builder()
            .id(config.getId())
            .projectId(config.getProjectId())
            .scoringMethod(config.getScoringMethod())
            .active(config.getActive())
            .build();
    }
}
