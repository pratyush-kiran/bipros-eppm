package com.bipros.hds.api.admin;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.hds.api.dto.HdsVersionDetailResponse;
import com.bipros.hds.api.dto.HdsVersionResponse;
import com.bipros.hds.application.ingestion.ProgressStreamRegistry;
import com.bipros.hds.application.library.HdsLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Admin endpoints for HDS version (PDF) management:
 *  - Streaming multipart upload + SHA-256 dedup
 *  - GET single version detail (including indexing error)
 *  - SSE progress feed (subscribes to {@link ProgressStreamRegistry})
 *  - Retry a failed indexing run
 *  - Delete a version (cascades chunks + MinIO blob)
 */
@RestController
@RequestMapping("/v1/hds/admin")
@RequiredArgsConstructor
public class HdsVersionAdminController {

    private final HdsLibraryService library;
    private final ProgressStreamRegistry progress;
    private final SecurityContextHelper securityContextHelper;

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.CREATE')")
    @PostMapping(value = "/documents/{docId}/versions", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<HdsVersionResponse>> upload(
            @PathVariable UUID docId,
            @RequestParam("versionLabel") String versionLabel,
            @RequestParam(value = "revisionYear", required = false) Integer revisionYear,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID userId = currentUserIdOrNull();
        try {
            var version = library.uploadVersion(docId, versionLabel, revisionYear,
                file.getInputStream(), file.getSize(), file.getOriginalFilename(), userId);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(HdsVersionResponse.from(version)));
        } catch (HdsLibraryService.DuplicateUploadException dup) {
            // SHA-256 already indexed — return the existing version with 409 so the UI can show
            // "this PDF was already uploaded as <existing>".
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.ok(HdsVersionResponse.from(dup.getExisting())));
        }
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping("/versions/{id}")
    public ResponseEntity<ApiResponse<HdsVersionDetailResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(HdsVersionDetailResponse.from(library.getVersion(id))));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping(value = "/versions/{id}/progress", produces = "text/event-stream")
    public SseEmitter progress(@PathVariable UUID id) {
        return progress.subscribe(id);
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.UPDATE')")
    @PostMapping("/versions/{id}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(@PathVariable UUID id) {
        library.retryVersion(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.DELETE')")
    @DeleteMapping("/versions/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        library.deleteVersion(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Resolves the calling user's UUID for {@link HdsLibraryService#uploadVersion}'s
     * {@code uploadedBy} audit column. Returns {@code null} if the security context is empty —
     * which happens in @WebMvcTest smoke tests or if the filter chain has been stripped — so we
     * don't fail uploads in those contexts.
     */
    private UUID currentUserIdOrNull() {
        try {
            return securityContextHelper.getCurrentUserId();
        } catch (Exception e) {
            // SecurityContextHelper throws IllegalStateException when no auth,
            // and IllegalArgumentException when the JWT principal name isn't a UUID
            // (the admin user's principal is the literal "admin" string).
            // Both are fine — fall back to null uploadedBy.
            return null;
        }
    }
}
