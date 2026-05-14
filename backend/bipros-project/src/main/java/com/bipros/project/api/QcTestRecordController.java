package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateQcSessionRequest;
import com.bipros.project.application.dto.QcDashboardResponse;
import com.bipros.project.application.dto.QcSessionResponse;
import com.bipros.project.application.dto.UpdateQcSessionRequest;
import com.bipros.project.application.service.QcSessionService;
import com.bipros.project.domain.model.QcOutcome;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/qc")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class QcTestRecordController {

    private final QcSessionService service;

    @GetMapping
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<List<QcSessionResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID activityId,
            @RequestParam(required = false) QcOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(projectId, activityId, outcome, from, to)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<QcSessionResponse>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.canEdit(#projectId)")
    public ResponseEntity<ApiResponse<QcSessionResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateQcSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.canEdit(#projectId)")
    public ResponseEntity<ApiResponse<QcSessionResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQcSessionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.canEdit(#projectId)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<QcDashboardResponse>> dashboard(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID activityId,
            @RequestParam(required = false) QcOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(service.dashboard(projectId, activityId, outcome, from, to)));
    }
}
