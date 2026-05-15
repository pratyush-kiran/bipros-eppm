package com.bipros.scheduling.api;

import com.bipros.scheduling.application.dto.PertEstimateRequest;
import com.bipros.scheduling.application.dto.PertEstimateResponse;
import com.bipros.scheduling.application.service.PertEstimateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/activities")
@Slf4j
@RequiredArgsConstructor
public class PertEstimateController {

  private final PertEstimateService pertEstimateService;

  @GetMapping("/{activityId}/pert-estimate")
  @PreAuthorize("hasPermission(null, 'SCHEDULE.READ')")
  public ResponseEntity<PertEstimateResponse> getPertEstimate(@PathVariable UUID activityId) {
    log.debug("GET /v1/activities/{}/pert-estimate", activityId);
    return ResponseEntity.ok(pertEstimateService.getByActivity(activityId));
  }

  @PostMapping("/{activityId}/pert-estimate")
  @PreAuthorize("hasPermission(null, 'SCHEDULE.UPDATE')")
  public ResponseEntity<PertEstimateResponse> createPertEstimate(
      @PathVariable UUID activityId,
      @RequestBody PertEstimateRequest request) {
    log.debug("POST /v1/activities/{}/pert-estimate", activityId);

    var updatedRequest = new PertEstimateRequest(
        activityId,
        request.optimisticDuration(),
        request.mostLikelyDuration(),
        request.pessimisticDuration()
    );

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(pertEstimateService.createOrUpdate(updatedRequest));
  }

  @PutMapping("/{activityId}/pert-estimate")
  @PreAuthorize("hasPermission(null, 'SCHEDULE.UPDATE')")
  public ResponseEntity<PertEstimateResponse> updatePertEstimate(
      @PathVariable UUID activityId,
      @RequestBody PertEstimateRequest request) {
    log.debug("PUT /v1/activities/{}/pert-estimate", activityId);

    var updatedRequest = new PertEstimateRequest(
        activityId,
        request.optimisticDuration(),
        request.mostLikelyDuration(),
        request.pessimisticDuration()
    );

    return ResponseEntity.ok(pertEstimateService.createOrUpdate(updatedRequest));
  }
}
