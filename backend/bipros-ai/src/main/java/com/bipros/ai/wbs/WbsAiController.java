package com.bipros.ai.wbs;

import com.bipros.ai.document.FilenameSanitizer;
import com.bipros.ai.job.WbsAiJob;
import com.bipros.ai.job.WbsAiJobService;
import com.bipros.ai.job.WbsAiJobView;
import com.bipros.ai.job.WbsAiJobWorker;
import com.bipros.ai.wbs.dto.CollisionResult;
import com.bipros.ai.wbs.dto.WbsAiApplyRequest;
import com.bipros.ai.wbs.dto.WbsAiGenerateFromDocumentRequest;
import com.bipros.ai.wbs.dto.WbsAiGenerateRequest;
import com.bipros.ai.wbs.dto.WbsAiGenerationResponse;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/v1/projects/{projectId}/wbs/ai")
@RequiredArgsConstructor
@Slf4j
public class WbsAiController {

    /**
     * Hard cap for AI document upload. Files > 5 MB go via OpenAI's Files API
     * (separate multipart upload, then referenced by file_id) instead of inline
     * base64 in the chat request body — that's how we get from a 10 MB inline
     * ceiling up to a real 50 MB cap.
     */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final WbsAiGenerationService wbsAiGenerationService;
    private final ObjectMapper objectMapper;
    private final WbsAiJobService jobService;
    private final WbsAiJobWorker jobWorker;

    @PostMapping("/generate")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
    public ResponseEntity<ApiResponse<WbsAiGenerationResponse>> generate(
            @PathVariable UUID projectId,
            @RequestBody WbsAiGenerateRequest req) {
        WbsAiGenerationResponse response = wbsAiGenerationService.generate(projectId, req);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Asynchronous from-document generation. Persists a job row, spills the
     * upload to a temp file, submits work to the bounded {@code wbsAiExecutor},
     * and returns 202 + jobId within ~200 ms — Tomcat threads stay free during
     * the 30–120 s LLM call.
     *
     * Frontend polls {@code GET .../jobs/{id}} until status is DONE / FAILED /
     * CANCELLED.
     */
    @PostMapping(value = "/generate-from-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
    public ResponseEntity<ApiResponse<JobAccepted>> generateFromDocument(
            @PathVariable UUID projectId,
            @RequestPart("metadata") String metadataJson,
            @RequestPart("file") MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("FILE_REQUIRED", "A document file is required.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessRuleException("FILE_TOO_LARGE",
                    "File exceeds the 50 MB limit for AI document upload.");
        }

        String safeFileName = FilenameSanitizer.sanitize(file.getOriginalFilename());
        WbsAiGenerateFromDocumentRequest metadata =
                objectMapper.readValue(metadataJson, WbsAiGenerateFromDocumentRequest.class);

        // Spill once on the request thread; the worker reads from disk via
        // streaming extractors. Worker owns lifecycle of the temp file from here.
        Path tempFile = Files.createTempFile("wbs-ai-doc-", suffixFor(safeFileName));
        try {
            file.transferTo(tempFile);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        WbsAiJob seed = new WbsAiJob();
        seed.setProjectId(projectId);
        seed.setFileName(safeFileName);
        seed.setFileSizeBytes(file.getSize());
        seed.setMimeType(file.getContentType());
        seed.setMetadataJson(metadataJson);
        seed.setProgressStage("PENDING");
        seed.setProgressPct(0);
        WbsAiJob saved = jobService.createPending(seed);

        try {
            jobWorker.submit(saved.getId(), tempFile, safeFileName, metadata);
        } catch (RejectedExecutionException e) {
            // Queue saturated: clean up and tell the caller to retry later.
            jobService.markFailed(saved.getId(), "JOB_QUEUE_FULL",
                    "AI generation queue is full; please retry in a moment.");
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            throw new BusinessRuleException("JOB_QUEUE_FULL",
                    "AI generation queue is currently full. Please retry in a moment.");
        }

        log.info("WBS AI job accepted: {} (project={}, file={}, {} bytes)",
                saved.getId(), projectId, safeFileName, file.getSize());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                new JobAccepted(saved.getId(), saved.getStatus().name())));
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.READ')")
    public ResponseEntity<ApiResponse<WbsAiJobView>> getJob(
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) throws IOException {
        WbsAiJob j = jobService.getOrThrow(jobId);
        if (!projectId.equals(j.getProjectId())) {
            throw new BusinessRuleException("JOB_NOT_FOUND", "Job not found in this project.");
        }
        WbsAiGenerationResponse result = null;
        if (j.getResultJson() != null && !j.getResultJson().isBlank()) {
            result = objectMapper.readValue(j.getResultJson(), WbsAiGenerationResponse.class);
        }
        WbsAiJobView view = new WbsAiJobView(
                j.getId(), j.getProjectId(), j.getStatus(),
                j.getProgressStage(), j.getProgressPct(),
                result, j.getErrorCode(), j.getErrorMessage(),
                j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt());
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    @DeleteMapping("/jobs/{jobId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> cancelJob(
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {
        WbsAiJob j = jobService.getOrThrow(jobId);
        if (!projectId.equals(j.getProjectId())) {
            throw new BusinessRuleException("JOB_NOT_FOUND", "Job not found in this project.");
        }
        jobService.requestCancel(jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
    public ResponseEntity<ApiResponse<List<CollisionResult>>> apply(
            @PathVariable UUID projectId,
            @RequestBody WbsAiApplyRequest req) {
        List<CollisionResult> results = wbsAiGenerationService.applyGenerated(projectId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(results));
    }

    private static String suffixFor(String safeFileName) {
        int dot = safeFileName.lastIndexOf('.');
        if (dot > 0 && dot < safeFileName.length() - 1) {
            String ext = safeFileName.substring(dot).toLowerCase();
            if (ext.equals(".pdf") || ext.equals(".xlsx") || ext.equals(".xls")) {
                return ext;
            }
        }
        return ".bin";
    }

    /** Body of the 202 response: { jobId, status }. */
    public record JobAccepted(UUID jobId, String status) {
    }
}
