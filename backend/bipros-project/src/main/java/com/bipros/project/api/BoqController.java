package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.BoqItemResponse;
import com.bipros.project.application.dto.BoqSummaryResponse;
import com.bipros.project.application.dto.CreateBoqItemRequest;
import com.bipros.project.application.dto.UpdateBoqItemRequest;
import com.bipros.project.application.service.BoqService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/boq")
@RequiredArgsConstructor
@Slf4j
public class BoqController {

  private final BoqService boqService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<BoqItemResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateBoqItemRequest request) {
    log.info("POST /v1/projects/{}/boq - itemNo={}", projectId, request.itemNo());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(boqService.createItem(projectId, request)));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<List<BoqItemResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateBoqItemRequest> requests) {
    log.info("POST /v1/projects/{}/boq/bulk - count={}", projectId, requests.size());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(boqService.createItemsBulk(projectId, requests)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<BoqSummaryResponse>> list(@PathVariable UUID projectId) {
    log.info("GET /v1/projects/{}/boq", projectId);
    return ResponseEntity.ok(ApiResponse.ok(boqService.getProjectBoqSummary(projectId)));
  }

  /**
   * BOQ candidates for an activity — used by the DPR form to suggest/pre-select a BOQ when
   * the supervisor picks an activity. The match is heuristic (activity name vs item description).
   */
  @GetMapping("/by-activity")
  public ResponseEntity<ApiResponse<List<BoqItemResponse>>> listForActivity(
      @PathVariable UUID projectId,
      @RequestParam UUID activityId) {
    log.info("GET /v1/projects/{}/boq/by-activity activityId={}", projectId, activityId);
    return ResponseEntity.ok(ApiResponse.ok(boqService.listForActivity(projectId, activityId)));
  }

  @GetMapping("/{itemId}")
  public ResponseEntity<ApiResponse<BoqItemResponse>> get(
      @PathVariable UUID projectId,
      @PathVariable UUID itemId) {
    return ResponseEntity.ok(ApiResponse.ok(boqService.getItem(projectId, itemId)));
  }

  @PatchMapping("/{itemId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<BoqItemResponse>> update(
      @PathVariable UUID projectId,
      @PathVariable UUID itemId,
      @Valid @RequestBody UpdateBoqItemRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(boqService.updateItem(projectId, itemId, request)));
  }

  @DeleteMapping("/{itemId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId,
      @PathVariable UUID itemId) {
    boqService.deleteItem(projectId, itemId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
