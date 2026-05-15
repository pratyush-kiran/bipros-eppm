package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.contract.application.dto.VariationOrderRequest;
import com.bipros.contract.application.dto.VariationOrderResponse;
import com.bipros.contract.application.service.VariationOrderService;
import com.bipros.contract.domain.model.VariationOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/contracts/{contractId}/variation-orders")
@RequiredArgsConstructor
public class VariationOrderController {

    private final VariationOrderService variationOrderService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.CREATE')")
    public ResponseEntity<ApiResponse<VariationOrderResponse>> create(
        @PathVariable UUID contractId,
        @Valid @RequestBody VariationOrderRequest request) {
        VariationOrderResponse response = variationOrderService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<VariationOrderResponse>>> listByContract(
        @PathVariable UUID contractId) {
        List<VariationOrderResponse> response = variationOrderService.listByContract(contractId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<VariationOrderResponse>> getById(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        VariationOrderResponse response = variationOrderService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<VariationOrderResponse>> update(
        @PathVariable UUID contractId,
        @PathVariable UUID id,
        @Valid @RequestBody VariationOrderRequest request) {
        VariationOrderResponse response = variationOrderService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasPermission(null, 'CONTRACT.APPROVE')")
    public ResponseEntity<ApiResponse<VariationOrderResponse>> updateStatus(
        @PathVariable UUID contractId,
        @PathVariable UUID id,
        @Valid @RequestBody UpdateVoStatusRequest request) {
        VariationOrderResponse response = variationOrderService.updateStatus(id, request.status(), request.approvedBy());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID contractId,
        @PathVariable UUID id) {
        variationOrderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record UpdateVoStatusRequest(
        @NotNull VariationOrderStatus status,
        String approvedBy
    ) {}
}
