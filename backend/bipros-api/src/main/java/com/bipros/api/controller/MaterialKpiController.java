package com.bipros.api.controller;

import com.bipros.api.service.MaterialKpiService;
import com.bipros.api.service.MaterialKpiService.MaterialKpiResponse;
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

@RestController
@RequestMapping("/v1/projects/{projectId}/kpis/material")
@RequiredArgsConstructor
public class MaterialKpiController {

  private final MaterialKpiService service;

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<MaterialKpiResponse>> getKpis(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(ApiResponse.ok(service.compute(projectId, from, to)));
  }
}
