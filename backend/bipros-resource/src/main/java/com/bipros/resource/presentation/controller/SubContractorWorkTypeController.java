package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.SubContractorWorkTypeDto;
import com.bipros.resource.application.service.SubContractorWorkTypeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/sc-work-types")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
@RequiredArgsConstructor
@Slf4j
public class SubContractorWorkTypeController {

  private final SubContractorWorkTypeService service;

  @GetMapping
  public ResponseEntity<ApiResponse<List<SubContractorWorkTypeDto>>> list(
      @RequestParam(required = false) String q) {
    List<SubContractorWorkTypeDto> result = q == null ? service.listAll() : service.search(q);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PostMapping("/find-or-create")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<SubContractorWorkTypeDto>> findOrCreate(
      @Valid @RequestBody FindOrCreateRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(
        service.findOrCreate(request.name(), request.defaultUnit())));
  }

  public record FindOrCreateRequest(
      @NotBlank(message = "name is required")
      String name,

      String defaultUnit
  ) {
  }
}
