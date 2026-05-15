package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateDailyWeatherRequest;
import com.bipros.project.application.dto.DailyWeatherResponse;
import com.bipros.project.application.service.DailyWeatherService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/weather")
@RequiredArgsConstructor
@Slf4j
public class DailyWeatherController {

  private final DailyWeatherService service;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<DailyWeatherResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateDailyWeatherRequest request) {
    log.info("POST /v1/projects/{}/weather - date={}", projectId, request.logDate());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(projectId, request)));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<List<DailyWeatherResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateDailyWeatherRequest> requests) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createBulk(projectId, requests)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<DailyWeatherResponse>>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, from, to)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<DailyWeatherResponse>> get(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    service.delete(projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
