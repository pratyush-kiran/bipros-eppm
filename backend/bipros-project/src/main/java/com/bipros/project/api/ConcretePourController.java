package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.ConcretePourResponse;
import com.bipros.project.application.dto.CreateConcretePourRequest;
import com.bipros.project.application.service.ConcretePourService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/concrete-pours")
@RequiredArgsConstructor
@Slf4j
public class ConcretePourController {

  private final ConcretePourService service;

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<Page<ConcretePourResponse>>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String site,
      Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, from, to, site, pageable)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<ConcretePourResponse>> get(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
  }

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<ConcretePourResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateConcretePourRequest request) {
    log.info("POST /v1/projects/{}/concrete-pours - date={}, site={}, qty={}",
        projectId, request.pourDate(), request.site(), request.quantityM3());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(projectId, request)));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<List<ConcretePourResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateConcretePourRequest> requests) {
    log.info("POST /v1/projects/{}/concrete-pours/bulk - {} row(s)",
        projectId, requests == null ? 0 : requests.size());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createBulk(projectId, requests)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    service.delete(projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @GetMapping("/totals/by-grade")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> totalsByGrade(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.totalsByGrade(projectId)));
  }

  @GetMapping("/totals/by-site")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> totalsBySite(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.totalsBySite(projectId)));
  }
}
