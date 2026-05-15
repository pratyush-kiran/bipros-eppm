package com.bipros.activity.api;

import com.bipros.activity.application.dto.ActivityResponse;
import com.bipros.activity.application.dto.CreateActivityRequest;
import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.service.ActivityService;
import com.bipros.activity.application.service.ApplyActualsRequest;
import com.bipros.activity.application.service.GlobalChangeRequest;
import com.bipros.activity.application.service.GlobalChangeService;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/activities")
// Class-level guard is just authentication. Per-method @projectAccess.hasProjectPermission
// enforces the project-scope ABAC. ActivityService also re-checks before any mutation
// (defense in depth — controller boundary fails fast, service is the source of truth).
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ActivityController {

  private final ActivityService activityService;
  private final GlobalChangeService globalChangeService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.CREATE')")
  public ResponseEntity<ApiResponse<ActivityResponse>> createActivity(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateActivityRequest request) {
    ActivityResponse response = activityService.createActivity(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<PagedResponse<ActivityResponse>>> listActivities(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "sortOrder") String sortBy,
      @RequestParam(defaultValue = "ASC") Sort.Direction direction) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
    PagedResponse<ActivityResponse> response = activityService.listActivities(projectId, pageable);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/{activityId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<ActivityResponse>> getActivity(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId) {
    ActivityResponse response = activityService.getActivity(activityId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{activityId}")
  // Reach-the-endpoint gate is ACTIVITY.UPDATE; ActivityService.updateActivity is the
  // source of truth: it allows the assignee to update their own activity even without
  // project-edit rights, and otherwise calls projectAccess.requireEdit.
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityResponse>> updateActivity(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId,
      @Valid @RequestBody UpdateActivityRequest request) {
    ActivityResponse response = activityService.updateActivity(activityId, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @DeleteMapping("/{activityId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.DELETE')")
  public ResponseEntity<Void> deleteActivity(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId) {
    activityService.deleteActivity(activityId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{activityId}/progress")
  // Progress updates are allowed for assignees even without full project-edit rights;
  // service performs the precise check.
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityResponse>> updateProgress(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId,
      @RequestParam Double percentComplete,
      @RequestParam(required = false) LocalDate actualStartDate,
      @RequestParam(required = false) LocalDate actualFinishDate) {
    ActivityResponse response = activityService.updateProgress(activityId, percentComplete,
        actualStartDate, actualFinishDate);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/apply-actuals")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> applyActuals(
      @PathVariable UUID projectId,
      @Valid @RequestBody ApplyActualsRequest request) {
    activityService.applyActuals(projectId, request.dataDate());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @PostMapping("/global-change")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> applyGlobalChange(
      @PathVariable UUID projectId,
      @Valid @RequestBody GlobalChangeRequest request) {
    int updatedCount = globalChangeService.applyGlobalChange(projectId, request);
    java.util.Map<String, Object> result = java.util.Map.of("updatedCount", updatedCount);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /**
   * Activities under this project that have no {@code work_activity_id} linked but either have
   * DPRs in the window or have planned dates intersecting the window. Powers the
   * "N activities need a Work Activity" banner on the Capacity Utilization page.
   */
  @GetMapping("/missing-work-activity")
  @PreAuthorize("@projectAccess.canRead(#projectId)")
  public ResponseEntity<ApiResponse<java.util.List<
      com.bipros.activity.application.dto.MissingWorkActivityRow>>> listMissingWorkActivity(
      @PathVariable UUID projectId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var rows = activityService.listMissingWorkActivity(projectId, from, to);
    return ResponseEntity.ok(ApiResponse.ok(rows));
  }

  /**
   * Per-activity supervisor assignment — Phase 4.5. Writes
   * {@code Activity.supervisor_user_id}. {@code supervisorUserId = null} clears.
   */
  @PutMapping("/{activityId}/supervisor")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<ActivityResponse>> setSupervisor(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId,
      @Valid @RequestBody com.bipros.activity.application.dto.SetSupervisorRequest request) {
    ActivityResponse response = activityService.setSupervisor(activityId, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /**
   * Bulk-assign one supervisor (a Resource) across many activities. Powers the
   * Resources → Supervisor sub-tab.
   */
  @PostMapping("/supervisor-bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> bulkSetSupervisor(
      @PathVariable UUID projectId,
      @Valid @RequestBody com.bipros.activity.application.dto.BulkSupervisorRequest request) {
    int updated = activityService.bulkSetSupervisor(projectId, request);
    return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of("updated", updated)));
  }
}
