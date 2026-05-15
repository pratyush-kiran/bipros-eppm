package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateResourceRateRequest;
import com.bipros.resource.application.dto.ResourceRateResponse;
import com.bipros.resource.application.dto.UnitRateMasterRow;
import com.bipros.resource.application.service.ResourceRateService;
import com.bipros.resource.application.service.UnitRateMasterService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Unit Rate Master (PMS MasterData Screen 05). Exposes the read-only register view plus
 * write operations delegated to {@link ResourceRateService}. The register itself is still
 * served by the existing {@code list} method; POST/PUT/DELETE accept the PMS payload with
 * budgeted/actual/variance fields.
 */
@RestController
@RequestMapping("/v1/unit-rate-master")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class UnitRateMasterController {

  private final UnitRateMasterService service;
  private final ResourceRateService rateService;

  @GetMapping
  @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
  public ResponseEntity<ApiResponse<List<UnitRateMasterRow>>> list(
      @RequestParam(required = false) String category) {
    log.info("GET /v1/unit-rate-master category={}", category);
    return ResponseEntity.ok(ApiResponse.ok(service.list(category)));
  }

  @PostMapping("/resources/{resourceId}/rates")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceRateResponse>> createRate(
      @PathVariable UUID resourceId,
      @Valid @RequestBody CreateResourceRateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(rateService.createRate(resourceId, request)));
  }

  @PutMapping("/rates/{rateId}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceRateResponse>> updateRate(
      @PathVariable UUID rateId,
      @Valid @RequestBody CreateResourceRateRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(rateService.updateRate(rateId, request)));
  }

  @DeleteMapping("/rates/{rateId}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteRate(@PathVariable UUID rateId) {
    rateService.deleteRate(rateId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @GetMapping("/resources/{resourceId}/rates")
  @PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
  public ResponseEntity<ApiResponse<List<ResourceRateResponse>>> listByResource(
      @PathVariable UUID resourceId) {
    return ResponseEntity.ok(ApiResponse.ok(rateService.listRatesByResource(resourceId)));
  }
}
