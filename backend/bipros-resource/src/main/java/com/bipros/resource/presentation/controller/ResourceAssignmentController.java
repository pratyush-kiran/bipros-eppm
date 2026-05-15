package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.AssignedResourcePickerOption;
import com.bipros.resource.application.dto.CreateResourceAssignmentRequest;
import com.bipros.resource.application.dto.ResourceAssignmentResponse;
import com.bipros.resource.application.dto.ResourceLevelingRequest;
import com.bipros.resource.application.dto.ResourceLevelingResponse;
import com.bipros.resource.application.dto.ResourceUsageEntry;
import com.bipros.resource.application.dto.StaffAssignmentRequest;
import com.bipros.resource.application.dto.SwapResourceRequest;
import com.bipros.resource.application.dto.UtilizationProfileEntry;
import com.bipros.resource.application.service.ResourceAssignmentService;
import com.bipros.resource.application.service.ResourceLevelingService;
import com.bipros.resource.domain.algorithm.LevelingMode;
import com.bipros.resource.domain.algorithm.LevelingResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/resource-assignments")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceAssignmentController {

  private final ResourceAssignmentService assignmentService;
  private final ResourceLevelingService levelingService;

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> assignResource(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateResourceAssignmentRequest request) {
    log.info("POST /v1/projects/{}/resource-assignments - Assigning resource: {}",
        projectId, request.resourceId());
    ResourceAssignmentResponse response = assignmentService.assignResource(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> getAssignment(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    log.info("GET /v1/projects/{}/resource-assignments/{} - Fetching assignment", projectId, id);
    ResourceAssignmentResponse response = assignmentService.getAssignment(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceAssignmentResponse>>> listAssignments(
      @PathVariable UUID projectId) {
    log.info("GET /v1/projects/{}/resource-assignments - Listing assignments", projectId);
    List<ResourceAssignmentResponse> response = assignmentService.getAssignmentsByProject(projectId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/activity/{activityId}")
  public ResponseEntity<ApiResponse<List<ResourceAssignmentResponse>>> listAssignmentsByActivity(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId) {
    log.info("GET /v1/projects/{}/resource-assignments/activity/{} - Listing assignments by activity",
        projectId, activityId);
    List<ResourceAssignmentResponse> response = assignmentService.getAssignmentsByActivity(activityId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /**
   * Picker-mode lookup powering the DPR drawer's Manpower / Equipment / Material searchable
   * dropdowns. Returns leaner {@link AssignedResourcePickerOption} rows enriched with a unit-rate
   * snapshot resolved at {@code reportDate}; filter the result client-side or via {@code kind}.
   */
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/activity/{activityId}/picker")
  public ResponseEntity<ApiResponse<List<AssignedResourcePickerOption>>> listPickerOptionsByActivity(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {
    log.info("GET /v1/projects/{}/resource-assignments/activity/{}/picker kind={} reportDate={}",
        projectId, activityId, kind, reportDate);
    List<AssignedResourcePickerOption> response = assignmentService.getPickerOptionsByActivity(
        activityId, kind, reportDate);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/resource/{resourceId}")
  public ResponseEntity<ApiResponse<List<ResourceAssignmentResponse>>> listAssignmentsByResource(
      @PathVariable UUID projectId,
      @PathVariable UUID resourceId) {
    log.info("GET /v1/projects/{}/resource-assignments/resource/{} - Listing assignments by resource",
        projectId, resourceId);
    List<ResourceAssignmentResponse> response = assignmentService.getAssignmentsByResource(resourceId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  @GetMapping("/resource/{resourceId}/usage-profile")
  public ResponseEntity<ApiResponse<List<ResourceUsageEntry>>> getResourceUsageProfile(
      @PathVariable UUID projectId,
      @PathVariable UUID resourceId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    log.info("GET /v1/projects/{}/resource-assignments/resource/{}/usage-profile - "
            + "Fetching usage profile, startDate={}, endDate={}",
        projectId, resourceId, startDate, endDate);
    List<ResourceUsageEntry> response = assignmentService.getResourceUsageProfile(
        resourceId, startDate, endDate);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping("/{id}/staff")
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> staffAssignment(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @Valid @RequestBody StaffAssignmentRequest request) {
    log.info("POST /v1/projects/{}/resource-assignments/{}/staff - Staffing assignment with resource: {}",
        projectId, id, request.resourceId());
    boolean override = request.override() != null && request.override();
    ResourceAssignmentResponse response = assignmentService.staffAssignment(id, request.resourceId(), override);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping("/{id}/swap")
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> swapResource(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @Valid @RequestBody SwapResourceRequest request) {
    log.info("POST /v1/projects/{}/resource-assignments/{}/swap - Swapping resource to: {}",
        projectId, id, request.resourceId());
    boolean override = request.override() != null && request.override();
    ResourceAssignmentResponse response = assignmentService.swapResource(id, request.resourceId(), override);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> updateAssignment(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @Valid @RequestBody CreateResourceAssignmentRequest request) {
    log.info("PUT /v1/projects/{}/resource-assignments/{} - Updating assignment", projectId, id);
    ResourceAssignmentResponse response = assignmentService.updateAssignment(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> removeAssignment(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    log.info("DELETE /v1/projects/{}/resource-assignments/{} - Removing assignment", projectId, id);
    assignmentService.removeAssignment(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping("/recompute-costs")
  public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> recomputeCosts(
      @PathVariable UUID projectId) {
    log.info("POST /v1/projects/{}/resource-assignments/recompute-costs", projectId);
    int updated = assignmentService.recomputeProjectCosts(projectId);
    return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of("updated", updated)));
  }

  /**
   * Re-budget action — explicit copy of current planned values into budgeted_units / budgeted_cost.
   * Used when the planner deliberately re-baselines the resource commitment (e.g. after a Variation
   * Order). Plan edits do not auto-update budgeted values; this endpoint is the only path.
   */
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping("/{id}/rebudget")
  public ResponseEntity<ApiResponse<ResourceAssignmentResponse>> rebudget(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    log.info("POST /v1/projects/{}/resource-assignments/{}/rebudget", projectId, id);
    ResourceAssignmentResponse response = assignmentService.rebudgetAssignment(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  @PostMapping("/level-resources")
  public ResponseEntity<ApiResponse<LevelingResult>> levelResources(
      @PathVariable UUID projectId) {
    log.info("POST /v1/projects/{}/resource-assignments/level-resources - Starting resource leveling",
        projectId);
    LevelingResult result = levelingService.levelResources(projectId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PostMapping("/level")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceLevelingResponse>> levelResourcesWithMode(
      @PathVariable UUID projectId,
      @Valid @RequestBody ResourceLevelingRequest request) {
    log.info("POST /v1/projects/{}/resource-assignments/level - mode={}", projectId, request.mode());
    ResourceLevelingResponse response = levelingService.levelResourcesWithMode(
        projectId, request.mode(), request.resourceIds());
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /**
   * Preview-only counterpart of {@code POST /level} — returns the same proposed shifts
   * without writing them. Lets the UI render a "what would change" view before the user
   * commits via {@code POST /level}.
   */
  @PostMapping("/level/preview")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceLevelingResponse>> previewLeveling(
      @PathVariable UUID projectId,
      @Valid @RequestBody ResourceLevelingRequest request) {
    log.info("POST /v1/projects/{}/resource-assignments/level/preview - mode={}", projectId, request.mode());
    ResourceLevelingResponse response = levelingService.previewLevelingWithMode(
        projectId, request.mode(), request.resourceIds());
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/utilization-profile")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
  public ResponseEntity<ApiResponse<List<UtilizationProfileEntry>>> getUtilizationProfile(
      @PathVariable UUID projectId) {
    log.info("GET /v1/projects/{}/resource-assignments/utilization-profile", projectId);
    List<UtilizationProfileEntry> response = levelingService.getUtilizationProfile(projectId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
