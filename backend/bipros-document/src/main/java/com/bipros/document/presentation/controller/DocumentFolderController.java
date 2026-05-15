package com.bipros.document.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.DocumentFolderRequest;
import com.bipros.document.application.dto.DocumentFolderResponse;
import com.bipros.document.application.service.DocumentFolderService;
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
@RequestMapping("/v1/projects/{projectId}/document-folders")
@RequiredArgsConstructor
public class DocumentFolderController {

    private final DocumentFolderService folderService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<DocumentFolderResponse>> createFolder(
        @PathVariable UUID projectId,
        @Valid @RequestBody DocumentFolderRequest request) {
        DocumentFolderResponse response = folderService.createFolder(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    @GetMapping("/{folderId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<DocumentFolderResponse>> getFolder(
        @PathVariable UUID projectId,
        @PathVariable UUID folderId) {
        DocumentFolderResponse response = folderService.getFolder(projectId, folderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/root")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<DocumentFolderResponse>>> listRootFolders(
        @PathVariable UUID projectId) {
        List<DocumentFolderResponse> response = folderService.listRootFolders(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{folderId}/children")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<DocumentFolderResponse>>> listChildFolders(
        @PathVariable UUID projectId,
        @PathVariable UUID folderId) {
        List<DocumentFolderResponse> response = folderService.listChildFolders(projectId, folderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{folderId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<DocumentFolderResponse>> updateFolder(
        @PathVariable UUID projectId,
        @PathVariable UUID folderId,
        @Valid @RequestBody DocumentFolderRequest request) {
        DocumentFolderResponse response = folderService.updateFolder(projectId, folderId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{folderId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteFolder(
        @PathVariable UUID projectId,
        @PathVariable UUID folderId) {
        folderService.deleteFolder(projectId, folderId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
