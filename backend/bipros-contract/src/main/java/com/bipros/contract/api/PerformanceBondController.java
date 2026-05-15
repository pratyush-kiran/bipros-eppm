package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.contract.application.dto.PerformanceBondRequest;
import com.bipros.contract.application.dto.PerformanceBondResponse;
import com.bipros.contract.application.service.PerformanceBondService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/contracts/{contractId}/bonds")
@RequiredArgsConstructor
public class PerformanceBondController {

    private final PerformanceBondService bondService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.CREATE')")
    public ResponseEntity<ApiResponse<PerformanceBondResponse>> create(
        @PathVariable UUID contractId,
        @Valid @RequestBody PerformanceBondRequest request) {
        PerformanceBondResponse response = bondService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<PerformanceBondResponse>>> listByContract(
        @PathVariable UUID contractId) {
        List<PerformanceBondResponse> response = bondService.listByContract(contractId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<PerformanceBondResponse>> getById(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        PerformanceBondResponse response = bondService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<PerformanceBondResponse>> update(
        @PathVariable UUID contractId,
        @PathVariable UUID id,
        @Valid @RequestBody PerformanceBondRequest request) {
        PerformanceBondResponse response = bondService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        bondService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
