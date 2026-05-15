package com.bipros.reporting.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.application.dto.AnalyticsQueryDto;
import com.bipros.reporting.application.dto.AnalyticsQueryRequest;
import com.bipros.reporting.application.service.AnalyticsQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/reporting/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

  private final AnalyticsQueryService analyticsQueryService;

  /**
   * Submit a natural language analytics query
   */
  @PostMapping("/query")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<AnalyticsQueryDto>> submitQuery(
      @Valid @RequestBody AnalyticsQueryRequest request) {
    String userId = "anonymous";
    AnalyticsQueryDto result = analyticsQueryService.processQuery(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
  }

  /**
   * Get query history for current user
   */
  @GetMapping("/queries")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<List<AnalyticsQueryDto>>> getQueryHistory(
      @RequestParam(defaultValue = "20") int limit) {
    String userId = "anonymous";
    List<AnalyticsQueryDto> history = analyticsQueryService.getQueryHistory(userId, limit);
    return ResponseEntity.ok(ApiResponse.ok(history));
  }
}
