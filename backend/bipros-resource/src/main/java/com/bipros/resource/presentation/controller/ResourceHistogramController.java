package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ResourceHistogramEntry;
import com.bipros.resource.application.service.ResourceHistogramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/resource-histogram")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceHistogramController {

  private final ResourceHistogramService resourceHistogramService;

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceHistogramEntry>>> getHistogram(
      @PathVariable UUID projectId,
      @RequestParam UUID resourceId,
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate) {
    log.info(
        "GET /v1/projects/{}/resource-histogram - Computing histogram, resourceId={}, fromDate={}, toDate={}",
        projectId,
        resourceId,
        fromDate,
        toDate);

    LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusMonths(12);
    LocalDate to = toDate != null ? toDate : LocalDate.now();

    List<ResourceHistogramEntry> response = resourceHistogramService.getHistogram(projectId, resourceId, from, to);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
