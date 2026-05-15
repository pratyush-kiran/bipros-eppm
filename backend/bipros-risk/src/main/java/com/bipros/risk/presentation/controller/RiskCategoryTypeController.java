package com.bipros.risk.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.risk.application.dto.CreateRiskCategoryTypeRequest;
import com.bipros.risk.application.dto.RiskCategoryTypeResponse;
import com.bipros.risk.application.dto.UpdateRiskCategoryTypeRequest;
import com.bipros.risk.application.service.RiskCategoryTypeService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/risk-category-types")
@RequiredArgsConstructor
public class RiskCategoryTypeController {

    private final RiskCategoryTypeService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RiskCategoryTypeResponse>>> listActive() {
        return ResponseEntity.ok(ApiResponse.ok(service.listActive()));
    }

    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<RiskCategoryTypeResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RiskCategoryTypeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskCategoryTypeResponse>> create(
        @Valid @RequestBody CreateRiskCategoryTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(request)));
    }

    @PutMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<RiskCategoryTypeResponse>> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateRiskCategoryTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
