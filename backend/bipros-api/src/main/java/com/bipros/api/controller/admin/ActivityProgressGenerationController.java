package com.bipros.api.controller.admin;

import com.bipros.api.dto.ActivityProgressGenerationRequest;
import com.bipros.api.dto.ActivityProgressGenerationResponse;
import com.bipros.api.service.ActivityProgressGenerationService;
import com.bipros.common.dto.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/projects/{projectId}")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ActivityProgressGenerationController {

  private final ActivityProgressGenerationService service;

  @PostMapping("/generate-activity-progress")
  public ResponseEntity<ApiResponse<ActivityProgressGenerationResponse>> generate(
      @PathVariable UUID projectId,
      @RequestBody(required = false) ActivityProgressGenerationRequest request) {
    ActivityProgressGenerationRequest req =
        request == null ? new ActivityProgressGenerationRequest() : request;
    log.info(
        "POST /v1/admin/projects/{}/generate-activity-progress dryRun={} band={}-{}",
        projectId, req.isDryRun(), req.getTargetPercentMin(), req.getTargetPercentMax());
    return ResponseEntity.ok(ApiResponse.ok(service.generate(projectId, req)));
  }
}
