package com.bipros.resource.presentation.controller.role;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.role.RoleAssignmentRequest;
import com.bipros.resource.application.dto.role.RoleAssignmentResponse;
import com.bipros.resource.application.service.role.RoleAssignmentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Role-based activity demand endpoints. New flow: activity demand is {@code role + variant +
 * headcount × duration} (manpower/equipment) or {@code role + variant + quantity} (material).
 *
 * <p>The legacy {@code /v1/projects/{projectId}/resource-assignments} controller remains for
 * historical reads and staff/swap operations on resource-based rows.
 */
@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('ADMIN', 'PROJECT_MANAGER', 'VIEWER')")
@RequiredArgsConstructor
@Slf4j
public class RoleAssignmentController {

  private final RoleAssignmentService service;

  @PostMapping("/projects/{projectId}/role-assignments")
  @PreAuthorize("hasAnyRole('ADMIN', 'PROJECT_MANAGER')")
  public ResponseEntity<ApiResponse<RoleAssignmentResponse>> create(
      @PathVariable UUID projectId, @Valid @RequestBody RoleAssignmentRequest req) {
    log.info("POST /v1/projects/{}/role-assignments role={} activity={}", projectId, req.roleId(), req.activityId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createRoleAssignment(projectId, req)));
  }

  @PutMapping("/role-assignments/{id}")
  @PreAuthorize("hasAnyRole('ADMIN', 'PROJECT_MANAGER')")
  public ResponseEntity<ApiResponse<RoleAssignmentResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody RoleAssignmentRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.updateRoleAssignment(id, req)));
  }

  @DeleteMapping("/role-assignments/{id}")
  @PreAuthorize("hasAnyRole('ADMIN', 'PROJECT_MANAGER')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    service.deleteRoleAssignment(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @GetMapping("/projects/{projectId}/activities/{activityId}/role-assignments")
  public ResponseEntity<ApiResponse<List<RoleAssignmentResponse>>> listForActivity(
      @PathVariable UUID projectId, @PathVariable UUID activityId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listForActivity(activityId)));
  }
}
