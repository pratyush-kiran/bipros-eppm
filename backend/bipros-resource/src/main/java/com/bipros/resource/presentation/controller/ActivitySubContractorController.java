package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ActivitySubContractorAssignmentResponse;
import com.bipros.resource.application.dto.CreateActivitySubContractorAssignmentRequest;
import com.bipros.resource.application.service.ActivitySubContractorAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ActivitySubContractorController {

  private final ActivitySubContractorAssignmentService service;

  @GetMapping("/projects/{projectId}/activities/{activityId}/sub-contractor-assignments")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  public ResponseEntity<ApiResponse<List<ActivitySubContractorAssignmentResponse>>> listForActivity(
      @PathVariable UUID projectId, @PathVariable UUID activityId) {
    log.info("GET /v1/projects/{}/activities/{}/sub-contractor-assignments", projectId, activityId);
    return ResponseEntity.ok(ApiResponse.ok(service.listForActivity(projectId, activityId)));
  }

  @PostMapping("/projects/{projectId}/sub-contractor-assignments")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ActivitySubContractorAssignmentResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateActivitySubContractorAssignmentRequest request) {
    log.info("POST /v1/projects/{}/sub-contractor-assignments activity={}",
        projectId, request.activityId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(projectId, request)));
  }

  @DeleteMapping("/sub-contractor-assignments/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    log.info("DELETE /v1/sub-contractor-assignments/{}", id);
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
