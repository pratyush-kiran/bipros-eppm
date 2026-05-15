package com.bipros.api.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * CC-5 — surfaces "acted as supervisor on N DPR rows" on the Resource detail page now that
 * {@code DailyProgressReport.supervisor_resource_id} is structured (Phase 7). Lives in
 * {@code bipros-api} because it crosses the resource and project domains.
 */
@RestController
@RequestMapping("/v1/resources/{resourceId}/dpr-summary")
@PreAuthorize("hasPermission(null, 'DPR.READ')")
@RequiredArgsConstructor
public class ResourceDprSummaryController {

  private final DailyProgressReportRepository dprRepository;

  public record ResourceDprSummary(
      UUID resourceId,
      int days,
      LocalDate sinceInclusive,
      long count
  ) {}

  @GetMapping
  @Transactional(readOnly = true)
  public ResponseEntity<ApiResponse<ResourceDprSummary>> get(
      @PathVariable UUID resourceId,
      @RequestParam(defaultValue = "30") int days) {
    int safeDays = Math.max(1, Math.min(days, 365));
    LocalDate since = LocalDate.now().minusDays(safeDays - 1L);
    long count = dprRepository.countBySupervisorUserIdAndReportDateGreaterThanEqual(
        resourceId, since);
    return ResponseEntity.ok(ApiResponse.ok(new ResourceDprSummary(resourceId, safeDays, since, count)));
  }
}
