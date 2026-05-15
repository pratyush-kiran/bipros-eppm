package com.bipros.cost.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.cost.application.dto.BudgetChangeLogResponse;
import com.bipros.cost.application.dto.CreateBudgetChangeRequest;
import com.bipros.cost.application.dto.ProjectBudgetResponse;
import com.bipros.cost.application.service.ProjectBudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/budget")
@RequiredArgsConstructor
public class ProjectBudgetController {

    private final ProjectBudgetService projectBudgetService;
    private final ProjectAccessGuard projectAccess;

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping
    public ResponseEntity<ApiResponse<ProjectBudgetResponse>> setInitialBudget(
            @PathVariable UUID projectId,
            @RequestBody Map<String, BigDecimal> body) {
        BigDecimal amount = body.get("amount");
        ProjectBudgetResponse response = projectBudgetService.setInitialBudget(projectId, amount);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping
    public ResponseEntity<ApiResponse<ProjectBudgetResponse>> getBudgetSummary(
            @PathVariable UUID projectId) {
        ProjectBudgetResponse response = projectBudgetService.getBudgetSummary(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.UPDATE')")
    @PostMapping("/changes")
    public ResponseEntity<ApiResponse<BudgetChangeLogResponse>> requestChange(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateBudgetChangeRequest request) {
        BudgetChangeLogResponse response = projectBudgetService.requestChange(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/changes")
    public ResponseEntity<ApiResponse<List<BudgetChangeLogResponse>>> getChangeLog(
            @PathVariable UUID projectId) {
        List<BudgetChangeLogResponse> response = projectBudgetService.getChangeLog(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.UPDATE')")
    @PatchMapping("/changes/{changeId}/approve")
    public ResponseEntity<ApiResponse<BudgetChangeLogResponse>> approveChange(
            @PathVariable UUID projectId,
            @PathVariable UUID changeId) {
        UUID decidedBy = projectAccess.currentUserId();
        BudgetChangeLogResponse response = projectBudgetService.approveChange(projectId, changeId, decidedBy);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.UPDATE')")
    @PatchMapping("/changes/{changeId}/reject")
    public ResponseEntity<ApiResponse<BudgetChangeLogResponse>> rejectChange(
            @PathVariable UUID projectId,
            @PathVariable UUID changeId,
            @RequestBody(required = false) Map<String, String> body) {
        UUID decidedBy = projectAccess.currentUserId();
        String reason = body != null ? body.get("reason") : null;
        BudgetChangeLogResponse response = projectBudgetService.rejectChange(projectId, changeId, decidedBy, reason);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
