package com.bipros.ai.voice.dpr;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Voice-driven DPR form-fill. The frontend records audio in the Add/Edit drawer, then POSTs the
 * blob along with the form's current state and the running session history. We transcribe, call
 * an LLM with a structured-output schema, and return either a patch of fields to merge or a
 * follow-up question.
 *
 * <p>Sessions are stateless on the server — the FE accumulates history and replays it on each
 * call. This keeps the endpoint horizontally scalable and avoids leaking transcripts into
 * persistence (transcripts are ephemeral by default; see PR2 plan, "Open items").
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/dpr/voice-fill")
@RequiredArgsConstructor
@Slf4j
public class DprVoiceFillController {

  private final DprVoiceFillService service;
  private final ObjectMapper objectMapper;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<DprVoiceFillResponse>> fill(
      @PathVariable UUID projectId,
      @NotNull @RequestPart("audio") MultipartFile audio,
      @RequestPart("state") String stateJson,
      @RequestPart(value = "history", required = false) String historyJson,
      @RequestPart(value = "dprId", required = false) String dprId) throws IOException {

    JsonNode state = objectMapper.readTree(stateJson);
    List<DprVoiceTurn> history = historyJson == null || historyJson.isBlank()
        ? List.of()
        : objectMapper.readValue(historyJson, new TypeReference<>() {});

    if (audio.isEmpty()) {
      throw new BusinessRuleException("VOICE_AUDIO_EMPTY", "Audio payload is empty");
    }

    log.info("POST /v1/projects/{}/dpr/voice-fill - audio={} bytes, history={} turn(s), dprId={}",
        projectId, audio.getSize(), history.size(), dprId);

    DprVoiceFillRequest request = new DprVoiceFillRequest(state, history, dprId);
    return ResponseEntity.ok(ApiResponse.ok(service.fill(projectId, audio, request)));
  }
}
