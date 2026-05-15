package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.contract.application.dto.BidSubmissionRequest;
import com.bipros.contract.application.dto.BidSubmissionResponse;
import com.bipros.contract.application.service.BidSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/tenders/{tenderId}/bids")
@RequiredArgsConstructor
public class BidSubmissionController {

    private final BidSubmissionService bidSubmissionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.CREATE')")
    public ResponseEntity<ApiResponse<BidSubmissionResponse>> create(
        @PathVariable UUID tenderId,
        @Valid @RequestBody BidSubmissionRequest request) {
        BidSubmissionResponse response = bidSubmissionService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<PagedResponse<BidSubmissionResponse>>> listByTender(
        @PathVariable UUID tenderId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        PagedResponse<BidSubmissionResponse> response = bidSubmissionService.listByTender(tenderId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<BidSubmissionResponse>>> listAllByTender(
        @PathVariable UUID tenderId) {
        List<BidSubmissionResponse> response = bidSubmissionService.listByTenderAll(tenderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<BidSubmissionResponse>> getById(
        @PathVariable UUID tenderId,
        @PathVariable UUID id) {
        BidSubmissionResponse response = bidSubmissionService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<BidSubmissionResponse>> update(
        @PathVariable UUID tenderId,
        @PathVariable UUID id,
        @Valid @RequestBody BidSubmissionRequest request) {
        BidSubmissionResponse response = bidSubmissionService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CONTRACT.DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID tenderId,
        @PathVariable UUID id) {
        bidSubmissionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
