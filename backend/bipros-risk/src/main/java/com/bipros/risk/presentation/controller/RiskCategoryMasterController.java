package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.risk.application.dto.CreateRiskCategoryMasterRequest;
import com.bipros.risk.application.dto.RiskCategoryMasterResponse;
import com.bipros.risk.application.dto.UpdateRiskCategoryMasterRequest;
import com.bipros.risk.application.service.RiskCategoryMasterService;
import com.bipros.risk.domain.model.Industry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/v1/risk-categories")
@RequiredArgsConstructor
public class RiskCategoryMasterController {

    private final RiskCategoryMasterService service;

    /**
     * Lists active risk categories. If {@code typeId} is supplied, narrows to that type.
     * If {@code industry} is supplied, filters to {@code industry IN (industry, GENERIC)}
     * — categories tagged GENERIC are always included so cross-cutting risks are visible.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RiskCategoryMasterResponse>>> list(
        @RequestParam(required = false) UUID typeId,
        @RequestParam(required = false) Industry industry) {
        if (typeId == null && industry == null) {
            return ResponseEntity.ok(ApiResponse.ok(service.listActive()));
        }
        return ResponseEntity.ok(ApiResponse.ok(service.listByTypeAndIndustry(typeId, industry)));
    }

    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<RiskCategoryMasterResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RiskCategoryMasterResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RiskCategoryMasterResponse>> getByCode(@PathVariable String code) {
        return service.findByCode(code)
            .map(r -> ResponseEntity.ok(ApiResponse.ok(r)))
            .orElseThrow(() -> new ResourceNotFoundException("RiskCategoryMaster", code));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskCategoryMasterResponse>> create(
        @Valid @RequestBody CreateRiskCategoryMasterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(request)));
    }

    @PutMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskCategoryMasterResponse>> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateRiskCategoryMasterRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
