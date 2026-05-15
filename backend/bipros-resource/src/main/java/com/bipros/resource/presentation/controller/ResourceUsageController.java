package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ResourceUsageTimePhasedResponse;
import com.bipros.resource.application.service.ResourceUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/v1/projects/{projectId}/resource-usage")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceUsageController {

  private final ResourceUsageService resourceUsageService;

  /**
   * Time-phased resource consumption tree (Resource Type → Resource → Activity), bucketed by
   * month between {@code from} and {@code to}. Both range params default to the project's planned
   * bounds (or MIN/MAX of activity dates as a fallback).
   */
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/time-phased")
  public ResponseEntity<ApiResponse<ResourceUsageTimePhasedResponse>> getTimePhased(
      @PathVariable UUID projectId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    log.info("GET /v1/projects/{}/resource-usage/time-phased — from={}, to={}", projectId, from, to);
    ResourceUsageTimePhasedResponse response = resourceUsageService.getTimePhased(projectId, from, to);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
