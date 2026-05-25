package com.bipros.hds.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.hds.api.dto.HdsChunkResponse;
import com.bipros.hds.api.dto.HdsVersionResponse;
import com.bipros.hds.api.dto.PresignedUrlResponse;
import com.bipros.hds.application.library.HdsLibraryService;
import com.bipros.hds.domain.HdsChunk;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.repo.HdsChunkRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only HDS query endpoints. Consumed by:
 *  - The Standards selector UI (versions list).
 *  - The PDF viewer (presigned-URL fetch + chunk lookup for citation hops).
 */
@RestController
@RequestMapping("/v1/hds")
@RequiredArgsConstructor
public class HdsQueryController {

    /** Presigned PDF URL TTL — short enough that a copied link expires before audit/handoff. */
    private static final Duration PRESIGNED_PDF_TTL = Duration.ofMinutes(10);

    private final HdsLibraryService library;
    private final HdsVersionRepository versionRepo;
    private final HdsChunkRepository chunkRepo;
    private final HdsStorageService storage;

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping("/versions")
    public ResponseEntity<ApiResponse<List<HdsVersionResponse>>> listIndexedVersions() {
        var versions = library.listIndexedVersions().stream().map(HdsVersionResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(versions));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping("/chunks/{id}")
    public ResponseEntity<ApiResponse<HdsChunkResponse>> getChunk(@PathVariable UUID id) {
        HdsChunk chunk = chunkRepo.findById(id).orElseThrow();
        HdsVersion version = versionRepo.findById(chunk.getHdsVersionId()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.ok(new HdsChunkResponse(
            chunk.getId(), version.getId(), version.getVersionLabel(),
            chunk.getPageStart(), chunk.getPageEnd(), chunk.getSectionPath(),
            chunk.getChunkType(), chunk.getContent())));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping("/versions/{id}/pdf")
    public ResponseEntity<ApiResponse<PresignedUrlResponse>> pdfUrl(@PathVariable UUID id) {
        HdsVersion v = versionRepo.findById(id).orElseThrow();
        var url = storage.presignGet(v.getStorageKey(), PRESIGNED_PDF_TTL);
        return ResponseEntity.ok(ApiResponse.ok(
            new PresignedUrlResponse(url.toString(), Instant.now().plus(PRESIGNED_PDF_TTL))));
    }
}
