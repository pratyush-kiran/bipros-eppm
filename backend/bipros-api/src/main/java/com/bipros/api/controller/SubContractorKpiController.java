package com.bipros.api.controller;

import com.bipros.api.service.SubContractorKpiService;
import com.bipros.api.service.SubContractorKpiService.SubContractorKpiResponse;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Sub-contractor KPIs for the Operational Insights tab. Single endpoint returns:
 *   - headline tiles (quantity completion, productivity, cost summary, etc.)
 *   - per (SC, work-type) detail rows
 *   - bottom-5 productivity / top-5 by cost / bottom-5 by output achievement panels
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/kpis/sub-contractor")
@RequiredArgsConstructor
public class SubContractorKpiController {

  private final SubContractorKpiService service;

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<SubContractorKpiResponse>> getKpis(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(ApiResponse.ok(service.compute(projectId, from, to)));
  }
}
