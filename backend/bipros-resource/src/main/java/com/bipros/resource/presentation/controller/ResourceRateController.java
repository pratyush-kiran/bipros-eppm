package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateResourceRateRequest;
import com.bipros.resource.application.dto.ResourceRateResponse;
import com.bipros.resource.application.service.ResourceRateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/resources/{resourceId}/rates")
@PreAuthorize("hasPermission(null, 'RESOURCE.READ')")
@RequiredArgsConstructor
@Slf4j
public class ResourceRateController {

  private final ResourceRateService rateService;

  @PostMapping
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceRateResponse>> createRate(
      @PathVariable UUID resourceId,
      @Valid @RequestBody CreateResourceRateRequest request) {
    log.info("POST /v1/resources/{}/rates - Creating rate, type={}", resourceId, request.rateType());
    ResourceRateResponse response = rateService.createRate(resourceId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ResourceRateResponse>> getRate(
      @PathVariable UUID resourceId,
      @PathVariable UUID id) {
    log.info("GET /v1/resources/{}/rates/{} - Fetching rate", resourceId, id);
    ResourceRateResponse response = rateService.getRate(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<ResourceRateResponse>>> listRates(
      @PathVariable UUID resourceId,
      @RequestParam(required = false) String rateType) {
    log.info("GET /v1/resources/{}/rates - Listing rates, type={}", resourceId, rateType);
    List<ResourceRateResponse> response;
    if (rateType != null) {
      response = rateService.listRatesByResourceAndType(resourceId, rateType);
    } else {
      response = rateService.listRatesByResource(resourceId);
    }
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.UPDATE')")
  public ResponseEntity<ApiResponse<ResourceRateResponse>> updateRate(
      @PathVariable UUID resourceId,
      @PathVariable UUID id,
      @Valid @RequestBody CreateResourceRateRequest request) {
    log.info("PUT /v1/resources/{}/rates/{} - Updating rate", resourceId, id);
    ResourceRateResponse response = rateService.updateRate(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'RESOURCE.DELETE')")
  public ResponseEntity<ApiResponse<Void>> deleteRate(
      @PathVariable UUID resourceId,
      @PathVariable UUID id) {
    log.info("DELETE /v1/resources/{}/rates/{} - Deleting rate", resourceId, id);
    rateService.deleteRate(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
