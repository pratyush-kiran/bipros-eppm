package com.bipros.api.controller;

import com.bipros.api.service.ManpowerKpiService;
import com.bipros.api.service.ManpowerKpiService.ManpowerKpiResponse;
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
 * Composite manpower KPIs for the Field / Operational / Executive dashboards. Single endpoint
 * returns workforce utilisation, productivity factor, labour cost-per-unit, and crew output
 * rows; the dashboards pick which slices to render.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/kpis/manpower")
@RequiredArgsConstructor
public class ManpowerKpiController {

  private final ManpowerKpiService service;

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<ManpowerKpiResponse>> getKpis(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(ApiResponse.ok(service.compute(projectId, from, to)));
  }
}
