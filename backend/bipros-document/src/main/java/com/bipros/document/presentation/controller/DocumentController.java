package com.bipros.document.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.DocumentDownload;
import com.bipros.document.application.dto.DocumentRequest;
import com.bipros.document.application.dto.DocumentResponse;
import com.bipros.document.application.dto.DocumentVersionRequest;
import com.bipros.document.application.dto.DocumentVersionResponse;
import com.bipros.document.application.dto.UploadDocumentRequest;
import com.bipros.document.application.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<DocumentResponse>> createDocument(
        @PathVariable UUID projectId,
        @Valid @RequestBody DocumentRequest request) {
        DocumentResponse response = documentService.createDocument(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    /**
     * Multipart upload of a new document. The metadata JSON is sent in a {@code metadata}
     * form part and the binary as a {@code file} form part.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.CREATE')")
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(
            @PathVariable UUID projectId,
            @Valid @RequestPart("metadata") UploadDocumentRequest metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        String uploadedBy = authentication != null ? authentication.getName() : "SYSTEM";
        DocumentResponse response = documentService.uploadDocument(projectId, metadata, file, uploadedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** Streams the current-version binary. */
    @GetMapping("/{documentId}/download")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID documentId,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition) {
        DocumentDownload dl = documentService.downloadDocument(projectId, documentId);
        ContentDisposition cd = ContentDisposition.builder("inline".equalsIgnoreCase(disposition) ? "inline" : "attachment")
                .filename(dl.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(dl.mimeType()))
                .contentLength(dl.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(dl.resource());
    }

    @GetMapping("/{documentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocument(
        @PathVariable UUID projectId,
        @PathVariable UUID documentId) {
        DocumentResponse response = documentService.getDocument(projectId, documentId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> listDocuments(
        @PathVariable UUID projectId) {
        List<DocumentResponse> response = documentService.listDocuments(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{documentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<DocumentResponse>> updateDocument(
        @PathVariable UUID projectId,
        @PathVariable UUID documentId,
        @Valid @RequestBody DocumentRequest request) {
        DocumentResponse response = documentService.updateDocument(projectId, documentId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
        @PathVariable UUID projectId,
        @PathVariable UUID documentId) {
        documentService.deleteDocument(projectId, documentId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{documentId}/versions")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<ApiResponse<List<DocumentVersionResponse>>> getVersions(
        @PathVariable UUID projectId,
        @PathVariable UUID documentId) {
        List<DocumentVersionResponse> response = documentService.getDocumentVersions(projectId, documentId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{documentId}/versions")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<DocumentVersionResponse>> addVersion(
        @PathVariable UUID projectId,
        @PathVariable UUID documentId,
        @Valid @RequestBody DocumentVersionRequest request) {
        String uploadedBy = "CURRENT_USER";
        DocumentVersionResponse response = documentService.addVersion(projectId, documentId, request, uploadedBy);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(response));
    }

    /**
     * Multipart upload of a new version of an existing document. Increments the currentVersion
     * counter and stores the binary under {@code v{n}/}.
     */
    @PostMapping(value = "/{documentId}/versions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.UPDATE')")
    public ResponseEntity<ApiResponse<DocumentVersionResponse>> uploadVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID documentId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "changeDescription", required = false) String changeDescription,
            Authentication authentication) {
        String uploadedBy = authentication != null ? authentication.getName() : "SYSTEM";
        DocumentVersionResponse response =
                documentService.uploadVersion(projectId, documentId, file, changeDescription, uploadedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** Streams a specific historical version binary. */
    @GetMapping("/{documentId}/versions/{versionNumber}/download")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DOCUMENT.READ')")
    public ResponseEntity<org.springframework.core.io.Resource> downloadVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID documentId,
            @PathVariable Integer versionNumber,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition) {
        DocumentDownload dl = documentService.downloadVersion(projectId, documentId, versionNumber);
        ContentDisposition cd = ContentDisposition.builder("inline".equalsIgnoreCase(disposition) ? "inline" : "attachment")
                .filename(dl.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(dl.mimeType()))
                .contentLength(dl.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(dl.resource());
    }
}
