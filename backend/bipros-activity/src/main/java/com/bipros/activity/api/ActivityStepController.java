package com.bipros.activity.api;

import com.bipros.activity.application.dto.ActivityStepResponse;
import com.bipros.activity.application.dto.CreateActivityStepRequest;
import com.bipros.activity.application.service.ActivityStepService;
import com.bipros.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/activities/{activityId}/steps")
@RequiredArgsConstructor
public class ActivityStepController {

  private final ActivityStepService stepService;

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ACTIVITY.CREATE')")
  public ResponseEntity<ApiResponse<ActivityStepResponse>> createStep(
      @PathVariable UUID activityId,
      @Valid @RequestBody CreateActivityStepRequest request) {
    ActivityStepResponse response = stepService.createStep(activityId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping
  @PreAuthorize("hasPermission(null, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<List<ActivityStepResponse>>> getSteps(
      @PathVariable UUID activityId) {
    List<ActivityStepResponse> response = stepService.getSteps(activityId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{stepId}")
  @PreAuthorize("hasPermission(null, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityStepResponse>> updateStep(
      @PathVariable UUID activityId,
      @PathVariable UUID stepId,
      @RequestParam String name,
      @RequestParam(required = false) String description,
      @RequestParam Double weight) {
    ActivityStepResponse response = stepService.updateStep(stepId, name, description, weight);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{stepId}/complete")
  @PreAuthorize("hasPermission(null, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityStepResponse>> completeStep(
      @PathVariable UUID activityId,
      @PathVariable UUID stepId) {
    ActivityStepResponse response = stepService.completeStep(stepId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{stepId}/uncomplete")
  @PreAuthorize("hasPermission(null, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityStepResponse>> uncompleteStep(
      @PathVariable UUID activityId,
      @PathVariable UUID stepId) {
    ActivityStepResponse response = stepService.uncompleteStep(stepId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @DeleteMapping("/{stepId}")
  @PreAuthorize("hasPermission(null, 'ACTIVITY.DELETE')")
  public ResponseEntity<Void> deleteStep(
      @PathVariable UUID activityId,
      @PathVariable UUID stepId) {
    stepService.deleteStep(stepId);
    return ResponseEntity.noContent().build();
  }
}
