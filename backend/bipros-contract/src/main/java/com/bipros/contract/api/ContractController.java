package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.contract.application.dto.ContractRequest;
import com.bipros.contract.application.dto.ContractResponse;
import com.bipros.contract.application.service.ContractKpiService;
import com.bipros.contract.application.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final ContractKpiService contractKpiService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.CREATE')")
    public ResponseEntity<ApiResponse<ContractResponse>> create(
        @PathVariable UUID projectId,
        @Valid @RequestBody ContractRequest request) {
        ContractResponse response = contractService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<PagedResponse<ContractResponse>>> listByProject(
        @PathVariable UUID projectId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        PagedResponse<ContractResponse> response = contractService.listByProject(projectId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<ContractResponse>> getById(
        @PathVariable UUID projectId,
        @PathVariable UUID id) {
        ContractResponse response = contractService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractResponse>> update(
        @PathVariable UUID projectId,
        @PathVariable UUID id,
        @Valid @RequestBody ContractRequest request) {
        ContractResponse response = contractService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID projectId,
        @PathVariable UUID id) {
        contractService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/kpi/refresh")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractResponse>> refreshKpi(
        @PathVariable UUID projectId,
        @PathVariable UUID id) {
        contractKpiService.refreshById(id);
        ContractResponse response = contractService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
