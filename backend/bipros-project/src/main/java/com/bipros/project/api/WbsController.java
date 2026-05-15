package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateWbsNodeRequest;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.application.dto.WbsNodeResponse;
import com.bipros.project.application.service.WbsBudgetService;
import com.bipros.project.application.service.WbsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/wbs")
@RequiredArgsConstructor
public class WbsController {

    private final WbsService wbsService;
    private final WbsBudgetService wbsBudgetService;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<WbsNodeResponse>>> getTree(@PathVariable UUID projectId) {
        List<WbsNodeResponse> tree = wbsService.getTree(projectId);
        return ResponseEntity.ok(ApiResponse.ok(tree));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<WbsNodeResponse>> getNode(@PathVariable UUID projectId, @PathVariable UUID id) {
        WbsNodeResponse response = wbsService.getNode(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<WbsNodeResponse>> createNode(
        @PathVariable UUID projectId,
        @Valid @RequestBody CreateWbsNodeRequest request) {
        // Path parameter wins; the body's projectId (if any) is ignored so callers don't have
        // to duplicate it (BUG-012).
        CreateWbsNodeRequest normalised = new CreateWbsNodeRequest(
            request.code(), request.name(), request.parentId(), projectId, request.obsNodeId(),
            request.wbsType(), request.wbsStatus(), request.wbsLevel(), request.budgetCrores());
        WbsNodeResponse response = wbsService.createNode(normalised);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<WbsNodeResponse>> updateNode(
        @PathVariable UUID projectId,
        @PathVariable UUID id,
        @Valid @RequestBody UpdateEpsNodeRequest request) {
        WbsNodeResponse response = wbsService.updateNode(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<Void> deleteNode(@PathVariable UUID projectId, @PathVariable UUID id) {
        wbsService.deleteNode(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/budget-summary")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<WbsBudgetService.WbsBudgetSummary>> getBudgetSummary(
            @PathVariable UUID projectId) {
        WbsBudgetService.WbsBudgetSummary response = wbsBudgetService.getBudgetSummary(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
