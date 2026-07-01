package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.ProjectSubContractorSummaryResponse;
import com.bipros.resource.application.dto.ProjectVendorSummaryResponse;
import com.bipros.resource.application.service.ProcurementSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProcurementSummaryController {

    private final ProcurementSummaryService service;

    @GetMapping("/projects/{projectId}/procurement/sub-contractors")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<List<ProjectSubContractorSummaryResponse>>> subContractors(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.subContractorSummary(projectId)));
    }

    @GetMapping("/projects/{projectId}/procurement/vendors")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'RESOURCE.READ')")
    public ResponseEntity<ApiResponse<List<ProjectVendorSummaryResponse>>> vendors(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.vendorSummary(projectId)));
    }
}
