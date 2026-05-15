package com.bipros.scheduling.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.scheduling.application.dto.CompressionAnalysisResponse;
import com.bipros.scheduling.application.service.ScheduleCompressionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/schedule-compression")
@RequiredArgsConstructor
@Slf4j
public class ScheduleCompressionController {

  private final ScheduleCompressionService compressionService;

  @PostMapping("/fast-track")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ResponseEntity<ApiResponse<CompressionAnalysisResponse>> analyzeFastTrack(
      @PathVariable UUID projectId) {
    log.info("Fast-track analysis requested for project: {}", projectId);
    CompressionAnalysisResponse result = compressionService.analyzeFastTrack(projectId);
    return ResponseEntity.status(HttpStatus.OK)
        .body(ApiResponse.ok(result));
  }

  @PostMapping("/crash")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ResponseEntity<ApiResponse<CompressionAnalysisResponse>> analyzeCrashing(
      @PathVariable UUID projectId) {
    log.info("Crashing analysis requested for project: {}", projectId);
    CompressionAnalysisResponse result = compressionService.analyzeCrashing(projectId);
    return ResponseEntity.status(HttpStatus.OK)
        .body(ApiResponse.ok(result));
  }
}
