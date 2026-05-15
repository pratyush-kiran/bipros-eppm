package com.bipros.document.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.DrawingRegisterRequest;
import com.bipros.document.application.dto.DrawingRegisterResponse;
import com.bipros.document.application.service.DrawingRegisterService;
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
@RequestMapping("/v1/projects/{projectId}/drawings")
@RequiredArgsConstructor
public class DrawingRegisterController {

    private final DrawingRegisterService drawingService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<DrawingRegisterResponse>> createDrawing(
        @PathVariable UUID projectId,
        @Valid @RequestBody DrawingRegisterRequest request) {
        DrawingRegisterResponse response = drawingService.createDrawing(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping("/{drawingId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<DrawingRegisterResponse>> getDrawing(
        @PathVariable UUID projectId,
        @PathVariable UUID drawingId) {
        DrawingRegisterResponse response = drawingService.getDrawing(projectId, drawingId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<DrawingRegisterResponse>>> listDrawings(
        @PathVariable UUID projectId) {
        List<DrawingRegisterResponse> response = drawingService.listDrawings(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{drawingId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<DrawingRegisterResponse>> updateDrawing(
        @PathVariable UUID projectId,
        @PathVariable UUID drawingId,
        @Valid @RequestBody DrawingRegisterRequest request) {
        DrawingRegisterResponse response = drawingService.updateDrawing(projectId, drawingId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{drawingId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteDrawing(
        @PathVariable UUID projectId,
        @PathVariable UUID drawingId) {
        drawingService.deleteDrawing(projectId, drawingId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
