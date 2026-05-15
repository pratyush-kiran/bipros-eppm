package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateDprIssueRequest;
import com.bipros.project.application.dto.DprIssueRow;
import com.bipros.project.application.dto.UpdateDprIssueRequest;
import com.bipros.project.application.service.DprIssueService;
import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated endpoints for DPR-issue mutations outside the parent DPR drawer:
 * status flips, reassignment, resolution close. Creation always routes through
 * {@code DailyProgressReportController}'s POST/PUT so no orphan issues can exist.
 *
 * <p>Issues remain editable here even after the parent DPR is in {@code APPROVED} state — the
 * approval lock is a quantity/cost concern; issues have a lifecycle that must outlive approval.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/dpr-issues")
@RequiredArgsConstructor
@Slf4j
public class DprIssueController {

    private final DprIssueService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PROJECT_MANAGER','SITE_SUPERVISOR')")
    public ResponseEntity<ApiResponse<DprIssueRow>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateDprIssueRequest request) {
        log.info("POST /v1/projects/{}/dpr-issues - category={}, severity={}", projectId, request.category(), request.severity());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DprIssueRow>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) IssueStatus status,
            @RequestParam(required = false) IssueSeverity severity,
            @RequestParam(required = false) IssueCategory category,
            @RequestParam(required = false) UUID supervisorUserId,
            @RequestParam(required = false) UUID activityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(
                projectId, status, severity, category, supervisorUserId, activityId, dateFrom, dateTo)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DprIssueRow>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
    public ResponseEntity<ApiResponse<DprIssueRow>> patch(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDprIssueRequest request) {
        log.info("PATCH /v1/projects/{}/dpr-issues/{} - status={}, severity={}", projectId, id, request.status(), request.severity());
        return ResponseEntity.ok(ApiResponse.ok(service.patch(projectId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        service.delete(projectId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
