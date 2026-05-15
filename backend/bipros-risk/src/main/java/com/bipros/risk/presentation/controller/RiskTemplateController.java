package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.CreateRiskTemplateRequest;
import com.bipros.risk.application.dto.RiskTemplateResponse;
import com.bipros.risk.application.dto.UpdateRiskTemplateRequest;
import com.bipros.risk.application.service.RiskTemplateService;
import com.bipros.risk.domain.model.Industry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/v1/risk-templates")
@RequiredArgsConstructor
@Slf4j
public class RiskTemplateController {

    private final RiskTemplateService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<RiskTemplateResponse>>> list(
        @RequestParam(required = false) Industry industry,
        @RequestParam(required = false) String projectCategory,
        @RequestParam(required = false) Boolean active) {
        log.info("GET /v1/risk-templates - industry={}, projectCategory={}, active={}",
            industry, projectCategory, active);
        return ResponseEntity.ok(ApiResponse.ok(service.list(industry, projectCategory, active)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<RiskTemplateResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskTemplateResponse>> create(
        @Valid @RequestBody CreateRiskTemplateRequest request) {
        log.info("POST /v1/risk-templates - code={}", request.code());
        RiskTemplateResponse created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskTemplateResponse>> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateRiskTemplateRequest request) {
        log.info("PUT /v1/risk-templates/{}", id);
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        log.info("DELETE /v1/risk-templates/{}", id);
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
