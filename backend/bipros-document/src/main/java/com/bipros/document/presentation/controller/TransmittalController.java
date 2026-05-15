package com.bipros.document.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.TransmittalItemRequest;
import com.bipros.document.application.dto.TransmittalItemResponse;
import com.bipros.document.application.dto.TransmittalRequest;
import com.bipros.document.application.dto.TransmittalResponse;
import com.bipros.document.application.service.TransmittalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/transmittals")
@RequiredArgsConstructor
public class TransmittalController {

    private final TransmittalService transmittalService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<TransmittalResponse>> createTransmittal(
        @PathVariable UUID projectId,
        @Valid @RequestBody TransmittalRequest request) {
        TransmittalResponse response = transmittalService.createTransmittal(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping("/{transmittalId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<TransmittalResponse>> getTransmittal(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId) {
        TransmittalResponse response = transmittalService.getTransmittal(projectId, transmittalId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<TransmittalResponse>>> listTransmittals(
        @PathVariable UUID projectId) {
        List<TransmittalResponse> response = transmittalService.listTransmittals(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{transmittalId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<TransmittalResponse>> updateTransmittal(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId,
        @Valid @RequestBody TransmittalRequest request) {
        TransmittalResponse response = transmittalService.updateTransmittal(projectId, transmittalId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{transmittalId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteTransmittal(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId) {
        transmittalService.deleteTransmittal(projectId, transmittalId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{transmittalId}/items")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<TransmittalItemResponse>>> getItems(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId) {
        List<TransmittalItemResponse> response = transmittalService.getTransmittalItems(projectId, transmittalId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{transmittalId}/items")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<TransmittalItemResponse>> addItem(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId,
        @Valid @RequestBody TransmittalItemRequest request) {
        TransmittalItemResponse response = transmittalService.addItem(projectId, transmittalId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @DeleteMapping("/{transmittalId}/items/{itemId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> removeItem(
        @PathVariable UUID projectId,
        @PathVariable UUID transmittalId,
        @PathVariable UUID itemId) {
        transmittalService.removeItem(projectId, transmittalId, itemId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
