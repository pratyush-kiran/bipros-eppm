package com.bipros.api.controller;

import com.bipros.api.service.RaBillDraftService;
import com.bipros.api.service.RaBillDraftService.DraftPreview;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 10 endpoint — generate (and optionally save) a draft RA Bill from BOQ + DPR data.
 * Cross-module aggregator: lives in {@code bipros-api} so it can read both BOQ ({@code
 * bipros-project}) and prior bills ({@code bipros-cost}) without making either domain
 * module depend on the other.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/ra-bills")
@RequiredArgsConstructor
public class RaBillDraftController {

  private final RaBillDraftService draftService;

  @PostMapping("/generate-draft")
  @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
  public ResponseEntity<ApiResponse<DraftPreview>> generateDraft(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID contractId,
      @RequestParam(defaultValue = "false") boolean save) {

    DraftPreview preview = draftService.generateDraft(projectId, from, to, contractId, save);
    return ResponseEntity.ok(ApiResponse.ok(preview));
  }
}
