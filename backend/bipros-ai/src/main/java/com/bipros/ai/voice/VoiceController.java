package com.bipros.ai.voice;

import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/ai")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'AI.WRITE')")
public class VoiceController {

    private final SpeechToTextService speechToTextService;

    @PostMapping(value = "/speech-to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<String>> speechToText(@RequestParam("audio") MultipartFile audio) {
        try {
            String text = speechToTextService.transcribe(
                    audio.getBytes(),
                    audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "audio.webm",
                    audio.getContentType() != null ? audio.getContentType() : "audio/webm"
            );
            return ResponseEntity.ok(ApiResponse.ok(text));
        } catch (Exception e) {
            throw new com.bipros.common.exception.BusinessRuleException("STT_UPLOAD_FAILED", "Failed to process audio: " + e.getMessage());
        }
    }
}
