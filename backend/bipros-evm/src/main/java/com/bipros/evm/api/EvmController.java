package com.bipros.evm.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.evm.application.dto.ActivityEvmResponse;
import com.bipros.evm.application.dto.CalculateEvmRequest;
import com.bipros.evm.application.dto.CostAccountRollupResponse;
import com.bipros.evm.application.dto.EvmCalculationResponse;
import com.bipros.evm.application.dto.EvmSummaryResponse;
import com.bipros.evm.application.dto.WbsEvmNode;
import com.bipros.evm.application.service.EvmRollupService;
import com.bipros.evm.application.service.EvmService;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/evm")
@RequiredArgsConstructor
public class EvmController {

    private final EvmService evmService;
    private final EvmRollupService evmRollupService;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.UPDATE')")
    @PostMapping("/calculate")
    public ResponseEntity<ApiResponse<EvmCalculationResponse>> calculateEvm(
            @PathVariable UUID projectId,
            @Valid @RequestBody CalculateEvmRequest request) {
        EvmCalculationResponse response = evmService.calculateEvm(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping
    public ResponseEntity<ApiResponse<EvmCalculationResponse>> getLatestEvm(
            @PathVariable UUID projectId) {
        EvmCalculationResponse response = evmService.getLatestEvm(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<EvmCalculationResponse>>> getEvmHistory(
            @PathVariable UUID projectId) {
        List<EvmCalculationResponse> response = evmService.getEvmHistory(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/wbs/{wbsNodeId}")
    public ResponseEntity<ApiResponse<EvmCalculationResponse>> getEvmByWbs(
            @PathVariable UUID projectId,
            @PathVariable UUID wbsNodeId) {
        EvmCalculationResponse response = evmService.getEvmByWbs(projectId, wbsNodeId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/activity/{activityId}")
    public ResponseEntity<ApiResponse<ActivityEvmResponse>> getActivityEvm(
            @PathVariable UUID projectId,
            @PathVariable UUID activityId) {
        ActivityEvmResponse response = evmService.getActivityEvm(projectId, activityId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<EvmSummaryResponse>> getSummary(
            @PathVariable UUID projectId) {
        EvmSummaryResponse response = evmService.getSummary(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/cost-account-rollup")
    public ResponseEntity<ApiResponse<List<CostAccountRollupResponse>>> getCostAccountRollup(
            @PathVariable UUID projectId) {
        List<CostAccountRollupResponse> response = evmService.getCostAccountRollup(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.UPDATE')")
    @PostMapping("/calculate-wbs")
    public ResponseEntity<ApiResponse<List<WbsEvmNode>>> calculateWbsEvm(
            @PathVariable UUID projectId,
            @Valid @RequestBody CalculateEvmRequest request) {
        List<WbsEvmNode> response = evmRollupService.calculateWbsTree(
                projectId, request.technique(), request.etcMethod());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
    @GetMapping("/wbs-tree")
    public ResponseEntity<ApiResponse<List<WbsEvmNode>>> getWbsTree(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "ACTIVITY_PERCENT_COMPLETE") EvmTechnique technique,
            @RequestParam(defaultValue = "CPI_BASED") EtcMethod etcMethod) {
        List<WbsEvmNode> response = evmRollupService.calculateWbsTree(projectId, technique, etcMethod);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
