package com.bipros.ai.voice;

import com.bipros.ai.provider.LlmProviderConfig;
import com.bipros.ai.provider.LlmProviderConfigRepository;
import com.bipros.ai.provider.crypto.ApiKeyCipher;
import com.bipros.common.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Speech-to-text using OpenAI-compatible Whisper API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeechToTextService {

    private final LlmProviderConfigRepository configRepository;
    private final ApiKeyCipher apiKeyCipher;
    private final RestTemplate restTemplate = new RestTemplate();

    public String transcribe(byte[] audioBytes, String filename, String mimeType) {
        LlmProviderConfig cfg = configRepository.findByIsDefaultTrueAndIsActiveTrue()
                .orElseThrow(() -> new BusinessRuleException("AI_NO_PROVIDER", "No active LLM provider configured"));

        String baseUrl = cfg.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/audio/transcriptions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKeyCipher.decrypt(cfg.getApiKeyIv(), cfg.getApiKeyCiphertext(), cfg.getApiKeyVersion()));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource audioResource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", audioResource);
        body.add("model", "whisper-1");
        body.add("response_format", "json");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map result = response.getBody();
            if (result != null && result.get("text") != null) {
                return result.get("text").toString();
            }
            throw new BusinessRuleException("STT_EMPTY", "Transcription returned empty text");
        } catch (Exception e) {
            log.error("STT transcription failed", e);
            throw new BusinessRuleException("STT_FAILED", "Speech-to-text failed: " + e.getMessage());
        }
    }
}
