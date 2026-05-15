package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CloseSnagRequest;
import com.bipros.siteops.application.dto.CreateSnagRequest;
import com.bipros.siteops.application.dto.SnagResponse;
import com.bipros.siteops.application.dto.UpdateSnagRequest;
import com.bipros.siteops.application.service.SnagService;
import com.bipros.siteops.domain.model.SnagStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/snags")
public class SnagController {

    private final SnagService snagService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SNAG.CREATE')")
    public ResponseEntity<ApiResponse<SnagResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateSnagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(snagService.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SNAG.READ')")
    public ResponseEntity<ApiResponse<List<SnagResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) SnagStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(snagService.listByProject(projectId, status)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SNAG.UPDATE')")
    public ResponseEntity<ApiResponse<SnagResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSnagRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(snagService.update(projectId, id, request)));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SNAG.CLOSE')")
    public ResponseEntity<ApiResponse<SnagResponse>> close(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) CloseSnagRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(snagService.close(projectId, id, request)));
    }
}
