package com.bipros.api.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.evm.application.dto.EvmSummaryResponse;
import com.bipros.evm.application.service.EvmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Dashboard-friendly EVM KPI endpoint. Wraps {@link EvmService#getSummary} with the broader
 * dashboard role set (the canonical {@code /v1/projects/{id}/evm/summary} endpoint is gated
 * to PROJECT_MANAGER + COST_ENGINEER, which excludes Programme Managers and Team Members
 * who legitimately need to read these KPIs from the Operational dashboard).
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/kpis/evm")
@RequiredArgsConstructor
public class EvmKpiController {

  private final EvmService evmService;

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'EVM.READ')")
  public ResponseEntity<ApiResponse<EvmSummaryResponse>> getEvmKpis(@PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(evmService.getSummary(projectId)));
  }
}
