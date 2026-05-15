package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.CreateRiskTriggerRequest;
import com.bipros.risk.application.dto.RiskTriggerDto;
import com.bipros.risk.application.service.RiskTriggerService;
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
@RequestMapping("/v1/projects/{projectId}/risk-triggers")
@RequiredArgsConstructor
public class RiskTriggerController {

    private final RiskTriggerService triggerService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.CREATE')")
    public ResponseEntity<ApiResponse<RiskTriggerDto>> createTrigger(
        @PathVariable UUID projectId,
        @Valid @RequestBody CreateRiskTriggerRequest request) {
        RiskTriggerDto result = triggerService.createTrigger(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(result));
    }

    @GetMapping("/{triggerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<RiskTriggerDto>> getTrigger(
        @PathVariable UUID projectId,
        @PathVariable UUID triggerId) {
        RiskTriggerDto result = triggerService.getTrigger(triggerId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskTriggerDto>>> listTriggers(
        @PathVariable UUID projectId) {
        List<RiskTriggerDto> results = triggerService.listProjectTriggers(projectId);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/triggered")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.READ')")
    public ResponseEntity<ApiResponse<List<RiskTriggerDto>>> listTriggeredRisks(
        @PathVariable UUID projectId) {
        List<RiskTriggerDto> results = triggerService.listTriggeredRisks(projectId);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @PostMapping("/evaluate")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<List<RiskTriggerDto>>> evaluateTriggers(
        @PathVariable UUID projectId) {
        List<RiskTriggerDto> results = triggerService.evaluateTriggers(projectId);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @PutMapping("/{triggerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.UPDATE')")
    public ResponseEntity<ApiResponse<RiskTriggerDto>> updateTrigger(
        @PathVariable UUID projectId,
        @PathVariable UUID triggerId,
        @Valid @RequestBody CreateRiskTriggerRequest request) {
        RiskTriggerDto result = triggerService.updateTrigger(triggerId, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/{triggerId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RISK.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteTrigger(
        @PathVariable UUID projectId,
        @PathVariable UUID triggerId) {
        triggerService.deleteTrigger(triggerId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
