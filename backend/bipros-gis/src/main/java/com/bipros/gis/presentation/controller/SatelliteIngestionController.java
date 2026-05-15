package com.bipros.gis.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.gis.application.dto.IngestionResult;
import com.bipros.gis.application.service.SatelliteIngestionService;
import com.bipros.gis.domain.model.SatelliteImage;
import com.bipros.gis.domain.model.SatelliteSceneIngestionLog;
import com.bipros.gis.domain.repository.SatelliteImageRepository;
import com.bipros.gis.domain.repository.SatelliteSceneIngestionLogRepository;
import com.bipros.integration.storage.RasterStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/gis")
@PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
@RequiredArgsConstructor
public class SatelliteIngestionController {

    private final SatelliteIngestionService ingestionService;
    private final SatelliteSceneIngestionLogRepository logRepository;
    private final SatelliteImageRepository imageRepository;
    private final RasterStorage rasterStorage;

    /**
     * Manual trigger used by the UI "Run Ingestion" button. Runs synchronously
     * and returns when all polygons have been processed. Typical duration:
     * 30-90 s for a 10-polygon project. For nightly runs, use the
     * SatelliteIngestionScheduler (Phase 4).
     */
    @PostMapping("/ingest")
    public ResponseEntity<ApiResponse<IngestionResult>> ingest(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) UUID polygonId
    ) {
        IngestionResult result = polygonId != null
            ? ingestionService.runForPolygon(projectId, polygonId, from, to)
            : ingestionService.runForProject(projectId, from, to);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** Recent ingestion runs for a project, newest first. Used by the UI's "last sync" indicator. */
    @GetMapping("/ingestion-log")
    public ResponseEntity<ApiResponse<List<SatelliteSceneIngestionLog>>> log(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(
            logRepository.findByProjectIdOrderByRunStartedAtDesc(projectId)));
    }

    /**
     * Stream the raster bytes for one satellite image back to the UI so the
     * gallery can render an actual thumbnail instead of the placeholder icon.
     * Serves whatever MIME the adapter stored (image/png for Sentinel-2, since
     * that's what we request in the evalscript). Authentication is enforced by
     * the class-level @PreAuthorize — the gallery passes the access token via
     * the standard Authorization header and renders the bytes through a blob URL.
     */
    @GetMapping("/satellite-images/{imageId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID projectId, @PathVariable UUID imageId) {
        SatelliteImage image = imageRepository.findById(imageId)
            .filter(i -> i.getProjectId().equals(projectId))
            .orElseThrow(() -> new ResourceNotFoundException("SatelliteImage", imageId.toString()));
        if (image.getFilePath() == null) {
            throw new ResourceNotFoundException("SatelliteImage.filePath", imageId.toString());
        }
        byte[] bytes = rasterStorage.get(URI.create(image.getFilePath()));
        MediaType mime = image.getMimeType() != null
            ? MediaType.parseMediaType(image.getMimeType())
            : MediaType.IMAGE_PNG;
        return ResponseEntity.ok()
            .contentType(mime)
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)))
            .body(bytes);
    }
}
