package com.bipros.api.controller;

import com.bipros.api.service.FieldDashboardSummaryService;
import com.bipros.api.service.FieldDashboardSummaryService.FieldSummaryResponse;
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
 * Top-strip + active-sites payload for the Field dashboard. Single endpoint feeds the four
 * headline KPI cards and the "Active sites" grid that previously rendered hardcoded mocks.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/dashboards/field")
@RequiredArgsConstructor
public class FieldDashboardController {

  private final FieldDashboardSummaryService service;

  @GetMapping("/summary")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<FieldSummaryResponse>> getSummary(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
    return ResponseEntity.ok(ApiResponse.ok(service.getSummary(projectId, asOfDate)));
  }
}
