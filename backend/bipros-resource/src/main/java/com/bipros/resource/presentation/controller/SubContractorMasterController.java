package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.SubContractorMasterResponse;
import com.bipros.resource.application.dto.SubContractorMasterWithMappingsRequest;
import com.bipros.resource.application.service.SubContractorMasterService;
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

@RestController
@RequestMapping("/v1/admin/sub-contractors")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
@RequiredArgsConstructor
@Slf4j
public class SubContractorMasterController {

  private final SubContractorMasterService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<SubContractorMasterResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<SubContractorMasterResponse>> get(@PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<SubContractorMasterResponse>> create(
      @Valid @RequestBody SubContractorMasterWithMappingsRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(request)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<SubContractorMasterResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody SubContractorMasterWithMappingsRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }
}
