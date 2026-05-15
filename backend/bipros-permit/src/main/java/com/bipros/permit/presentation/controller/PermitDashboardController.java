package com.bipros.permit.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.permit.application.dto.DashboardSummaryResponse;
import com.bipros.permit.application.service.PermitDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PermitDashboardController {

    private final PermitDashboardService dashboardService;

    @GetMapping("/v1/permits/dashboard-summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary(
            @RequestParam(required = false) UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.summary(projectId)));
    }
}
