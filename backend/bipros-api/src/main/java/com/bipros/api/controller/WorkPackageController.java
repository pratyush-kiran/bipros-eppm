package com.bipros.api.controller;

import com.bipros.api.dto.WorkPackageRowResponse;
import com.bipros.api.service.WorkPackageRollupService;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only endpoint backing the Work Packages list page. Returns a flat row per leaf WBS node
 * with activity- and EVM-derived metrics joined in.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/work-packages")
@RequiredArgsConstructor
public class WorkPackageController {

    private final WorkPackageRollupService service;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<WorkPackageRowResponse>>> list(
        @PathVariable UUID projectId
    ) {
        List<WorkPackageRowResponse> rows = service.listWorkPackages(projectId);
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }
}
