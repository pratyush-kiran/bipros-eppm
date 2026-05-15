package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.ChecklistDecisionRequest;
import com.bipros.siteops.application.dto.ChecklistInstanceResponse;
import com.bipros.siteops.application.dto.SaveChecklistAnswersRequest;
import com.bipros.siteops.application.dto.StartChecklistRequest;
import com.bipros.siteops.application.service.ChecklistService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/checklists")
public class ChecklistController {

    private final ChecklistService service;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.CREATE')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> start(
            @PathVariable UUID projectId,
            @Valid @RequestBody StartChecklistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.start(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.READ')")
    public ResponseEntity<ApiResponse<List<ChecklistInstanceResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(projectId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.READ')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
    }

    @PutMapping("/{id}/answers")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.UPDATE')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> saveAnswers(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody SaveChecklistAnswersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.saveAnswers(projectId, id, request)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.UPDATE')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> submit(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.submit(projectId, id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.APPROVE')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> approve(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) ChecklistDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.approve(projectId, id, request)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CHECKLIST.APPROVE')")
    public ResponseEntity<ApiResponse<ChecklistInstanceResponse>> reject(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody(required = false) ChecklistDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.reject(projectId, id, request)));
    }
}
