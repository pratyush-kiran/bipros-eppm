package com.bipros.activity.api;

import com.bipros.activity.application.dto.CreateRelationshipRequest;
import com.bipros.activity.application.dto.RelationshipResponse;
import com.bipros.activity.application.dto.UpdateRelationshipRequest;
import com.bipros.activity.application.service.RelationshipService;
import com.bipros.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/relationships")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RelationshipController {

  private final RelationshipService relationshipService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.CREATE')")
  public ResponseEntity<ApiResponse<RelationshipResponse>> createRelationship(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateRelationshipRequest request) {
    RelationshipResponse response = relationshipService.createRelationship(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<List<RelationshipResponse>>> getRelationships(
      @PathVariable UUID projectId) {
    List<RelationshipResponse> response = relationshipService.getRelationships(projectId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /** Fetch a single relationship by id for consistency with other resource-by-id endpoints. */
  @GetMapping("/{relationshipId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<RelationshipResponse>> getRelationship(
      @PathVariable UUID projectId,
      @PathVariable UUID relationshipId) {
    return ResponseEntity.ok(ApiResponse.ok(relationshipService.getRelationship(relationshipId)));
  }

  @PutMapping("/{relationshipId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
  public ResponseEntity<ApiResponse<RelationshipResponse>> updateRelationship(
      @PathVariable UUID projectId,
      @PathVariable UUID relationshipId,
      @Valid @RequestBody UpdateRelationshipRequest request) {
    RelationshipResponse updated = relationshipService.update(projectId, relationshipId, request);
    return ResponseEntity.ok(ApiResponse.ok(updated));
  }

  @DeleteMapping("/{relationshipId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.DELETE')")
  public ResponseEntity<Void> deleteRelationship(
      @PathVariable UUID projectId,
      @PathVariable UUID relationshipId) {
    relationshipService.deleteRelationship(relationshipId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/predecessors/{activityId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<List<RelationshipResponse>>> getPredecessors(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId) {
    List<RelationshipResponse> response = relationshipService.getPredecessors(activityId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/successors/{activityId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
  public ResponseEntity<ApiResponse<List<RelationshipResponse>>> getSuccessors(
      @PathVariable UUID projectId,
      @PathVariable UUID activityId) {
    List<RelationshipResponse> response = relationshipService.getSuccessors(activityId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
