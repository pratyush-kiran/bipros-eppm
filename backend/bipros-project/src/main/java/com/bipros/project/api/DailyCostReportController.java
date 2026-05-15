package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.DailyCostReportResponse;
import com.bipros.project.application.service.DailyCostReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/v1/projects/{projectId}/daily-cost-report")
@RequiredArgsConstructor
@Slf4j
public class DailyCostReportController {

  private final DailyCostReportService service;

  @GetMapping
  @PreAuthorize("@projectAccess.canRead(#projectId)")
  public ResponseEntity<ApiResponse<DailyCostReportResponse>> generate(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    log.info("GET /v1/projects/{}/daily-cost-report from={} to={}", projectId, from, to);
    return ResponseEntity.ok(ApiResponse.ok(service.generate(projectId, from, to)));
  }
}
