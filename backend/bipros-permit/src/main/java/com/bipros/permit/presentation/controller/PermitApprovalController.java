package com.bipros.permit.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.permit.application.dto.ApprovalActionRequest;
import com.bipros.permit.application.dto.ClosePermitRequest;
import com.bipros.permit.application.dto.GasTestDto;
import com.bipros.permit.application.dto.GasTestRequest;
import com.bipros.permit.application.dto.IsolationPointDto;
import com.bipros.permit.application.dto.IsolationPointRequest;
import com.bipros.permit.application.dto.PermitDetailResponse;
import com.bipros.permit.application.dto.RejectionRequest;
import com.bipros.permit.application.dto.RevokePermitRequest;
import com.bipros.permit.application.dto.SuspendPermitRequest;
import com.bipros.permit.application.service.PermitApprovalService;
import com.bipros.permit.application.service.PermitClosureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/v1/permits")
@RequiredArgsConstructor
public class PermitApprovalController {

    private final PermitApprovalService approvalService;
    private final PermitClosureService closureService;

    @PostMapping("/{permitId}/approvals/{stepNo}/approve")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> approve(
            @PathVariable UUID permitId,
            @PathVariable int stepNo,
            @Valid @RequestBody ApprovalActionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.approveStep(permitId, stepNo, request.remarks())));
    }

    @PostMapping("/{permitId}/approvals/{stepNo}/reject")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> reject(
            @PathVariable UUID permitId,
            @PathVariable int stepNo,
            @Valid @RequestBody RejectionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.rejectStep(permitId, stepNo, request.reason())));
    }

    @PostMapping("/{permitId}/gas-tests")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> recordGasTest(
            @PathVariable UUID permitId,
            @Valid @RequestBody GasTestRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.recordGasTest(permitId, request)));
    }

    @GetMapping("/{permitId}/gas-tests")
    @PreAuthorize("hasPermission(null, 'PERMIT.READ')")
    public ResponseEntity<ApiResponse<List<GasTestDto>>> listGasTests(@PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.listGasTests(permitId)));
    }

    @PostMapping("/{permitId}/isolation-points")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<IsolationPointDto>> applyIsolation(
            @PathVariable UUID permitId,
            @Valid @RequestBody IsolationPointRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.applyIsolation(permitId, request)));
    }

    @GetMapping("/{permitId}/isolation-points")
    @PreAuthorize("hasPermission(null, 'PERMIT.READ')")
    public ResponseEntity<ApiResponse<List<IsolationPointDto>>> listIsolation(@PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.listIsolationPoints(permitId)));
    }

    @PutMapping("/{permitId}/isolation-points/{pointId}/remove")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<IsolationPointDto>> removeIsolation(
            @PathVariable UUID permitId,
            @PathVariable UUID pointId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.removeIsolation(permitId, pointId)));
    }

    @PostMapping("/{permitId}/issue")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> issue(@PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.issue(permitId)));
    }

    @PostMapping("/{permitId}/start")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> start(@PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalService.start(permitId)));
    }

    @PostMapping("/{permitId}/close")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> close(
            @PathVariable UUID permitId,
            @Valid @RequestBody ClosePermitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(closureService.close(permitId, request.remarks())));
    }

    @PostMapping("/{permitId}/revoke")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> revoke(
            @PathVariable UUID permitId,
            @Valid @RequestBody RevokePermitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(closureService.revoke(permitId, request.reason())));
    }

    @PostMapping("/{permitId}/suspend")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> suspend(
            @PathVariable UUID permitId,
            @Valid @RequestBody SuspendPermitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(closureService.suspend(permitId, request.reason())));
    }

    @PostMapping("/{permitId}/resume")
    @PreAuthorize("hasPermission(null, 'PERMIT.APPROVE')")
    public ResponseEntity<ApiResponse<PermitDetailResponse>> resume(@PathVariable UUID permitId) {
        return ResponseEntity.ok(ApiResponse.ok(closureService.resume(permitId)));
    }
}
