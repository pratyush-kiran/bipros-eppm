package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateResourceCurveRequest;
import com.bipros.resource.application.dto.ResourceCurveResponse;
import com.bipros.resource.application.service.ResourceCurveService;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/resource-curves")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceCurveController {

  private final ResourceCurveService curveService;

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceCurveResponse>> createCurve(
      @Valid @RequestBody CreateResourceCurveRequest request) {
    log.info("POST /v1/resource-curves - Creating curve: {}", request.name());
    ResourceCurveResponse response = curveService.createCurve(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceCurveResponse>> getCurve(@PathVariable UUID id)
      throws JsonProcessingException {
    log.info("GET /v1/resource-curves/{} - Fetching curve", id);
    ResourceCurveResponse response = curveService.getCurve(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceCurveResponse>>> listCurves()
      throws JsonProcessingException {
    log.info("GET /v1/resource-curves - Listing curves");
    List<ResourceCurveResponse> response = curveService.listCurves();
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/defaults")
  public ResponseEntity<ApiResponse<List<ResourceCurveResponse>>> listDefaultCurves()
      throws JsonProcessingException {
    log.info("GET /v1/resource-curves/defaults - Listing default curves");
    List<ResourceCurveResponse> response = curveService.listDefaultCurves();
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceCurveResponse>> updateCurve(
      @PathVariable UUID id,
      @Valid @RequestBody CreateResourceCurveRequest request)
      throws JsonProcessingException {
    log.info("PUT /v1/resource-curves/{} - Updating curve", id);
    ResourceCurveResponse response = curveService.updateCurve(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteCurve(@PathVariable UUID id) {
    log.info("DELETE /v1/resource-curves/{} - Deleting curve", id);
    curveService.deleteCurve(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @PostMapping("/seed-defaults")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> seedDefaultCurves() {
    log.info("POST /v1/resource-curves/seed-defaults - Seeding default curves");
    curveService.seedDefaultCurves();
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(null));
  }
}
