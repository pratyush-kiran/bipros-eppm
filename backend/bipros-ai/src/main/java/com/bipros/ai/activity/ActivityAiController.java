package com.bipros.ai.activity;

import com.bipros.ai.activity.dto.ActivityAiApplyRequest;
import com.bipros.ai.activity.dto.ActivityAiApplyResponse;
import com.bipros.ai.activity.dto.ActivityAiGenerateFromDocumentRequest;
import com.bipros.ai.activity.dto.ActivityAiGenerateRequest;
import com.bipros.ai.activity.dto.ActivityAiGenerationResponse;
import com.bipros.ai.document.FilenameSanitizer;
import com.bipros.ai.document.MimeTypeDetector;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/activities/ai")
@RequiredArgsConstructor
@Slf4j
public class ActivityAiController {

    /**
     * Hard cap on activity-AI document upload. Same 50 MB ceiling as WBS uses;
     * Files API kicks in above 5 MB so request-body limits aren't hit.
     */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ActivityAiGenerationService activityAiGenerationService;
    private final ObjectMapper objectMapper;
    private final MimeTypeDetector mimeTypeDetector;

    @PostMapping("/generate")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'AI.READ')")
    public ResponseEntity<ApiResponse<ActivityAiGenerationResponse>> generate(
            @PathVariable UUID projectId,
            @RequestBody ActivityAiGenerateRequest req) {
        ActivityAiGenerationResponse response = activityAiGenerationService.generate(projectId, req);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping(value = "/generate-from-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'AI.READ')")
    public ResponseEntity<ApiResponse<ActivityAiGenerationResponse>> generateFromDocument(
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
        ActivityAiGenerateFromDocumentRequest metadata =
                objectMapper.readValue(metadataJson, ActivityAiGenerateFromDocumentRequest.class);

        Path tempFile = Files.createTempFile("activity-ai-doc-", suffixFor(safeFileName));
        try {
            file.transferTo(tempFile);
            String detectedMime = mimeTypeDetector.detectAndValidate(tempFile, safeFileName);
            ActivityAiGenerationResponse response = activityAiGenerationService.generateFromDocument(
                    projectId, metadata, tempFile, detectedMime, safeFileName);
            return ResponseEntity.ok(ApiResponse.ok(response));
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
            }
        }
    }

    @PostMapping("/apply")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ACTIVITY.UPDATE')")
    public ResponseEntity<ApiResponse<ActivityAiApplyResponse>> apply(
            @PathVariable UUID projectId,
            @RequestBody ActivityAiApplyRequest req) {
        ActivityAiApplyResponse response = activityAiGenerationService.applyGenerated(projectId, req);
        return ResponseEntity.ok(ApiResponse.ok(response));
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
}
