package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.contract.application.dto.ContractMilestoneRequest;
import com.bipros.contract.application.dto.ContractMilestoneResponse;
import com.bipros.contract.application.service.ContractMilestoneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/contracts/{contractId}/milestones")
@RequiredArgsConstructor
public class ContractMilestoneController {

    private final ContractMilestoneService milestoneService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.CREATE')")
    public ResponseEntity<ApiResponse<ContractMilestoneResponse>> create(
        @PathVariable UUID contractId,
        @Valid @RequestBody ContractMilestoneRequest request) {
        ContractMilestoneResponse response = milestoneService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<ContractMilestoneResponse>>> listByContract(
        @PathVariable UUID contractId) {
        List<ContractMilestoneResponse> response = milestoneService.listByContract(contractId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<ContractMilestoneResponse>> getById(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        ContractMilestoneResponse response = milestoneService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractMilestoneResponse>> update(
        @PathVariable UUID contractId,
        @PathVariable UUID id,
        @Valid @RequestBody ContractMilestoneRequest request) {
        ContractMilestoneResponse response = milestoneService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        milestoneService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
