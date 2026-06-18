package com.bipros.admin.presentation.controller;

import com.bipros.admin.application.dto.BrandingDto;
import com.bipros.admin.application.service.BrandingService;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no-auth) branding endpoint. Access is granted centrally via
 * SecurityConfig ({@code /v1/public/**} permitAll) — no @PreAuthorize here so
 * bipros-admin stays free of the security dependency.
 */
@RestController
@RequestMapping("/v1/public")
@RequiredArgsConstructor
public class BrandingController {

    private final BrandingService brandingService;

    @GetMapping("/branding")
    public ResponseEntity<ApiResponse<BrandingDto>> branding() {
        return ResponseEntity.ok(ApiResponse.ok(brandingService.resolveActive()));
    }
}
