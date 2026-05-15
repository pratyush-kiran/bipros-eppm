package com.bipros.ai.image;

import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/v1/ai")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'AI.WRITE')")
public class ImageController {

    private final AiImageStorageService imageStorageService;

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadResponse>> uploadImage(
            @RequestParam("conversationId") UUID conversationId,
            @RequestParam("image") MultipartFile image) {
        AiImageStorageService.StoredImage stored = imageStorageService.store(conversationId, image);
        String publicUrl = "/v1/ai/images/" + stored.relativePath();
        return ResponseEntity.ok(ApiResponse.ok(new UploadResponse(stored.fileName(), publicUrl, stored.mimeType())));
    }

    @GetMapping("/images/{conversationId}/{fileName}")
    public ResponseEntity<Resource> serveImage(
            @PathVariable UUID conversationId,
            @PathVariable String fileName) {
        String relativePath = conversationId + "/" + fileName;
        Resource resource = imageStorageService.load(relativePath);
        String contentType = determineContentType(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String determineContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    public record UploadResponse(String fileName, String url, String mimeType) {
    }
}
