package com.bipros.ai.voice.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.provider.LlmProvider;
import com.bipros.ai.provider.LlmProviderConfig;
import com.bipros.ai.provider.LlmProviderConfigRepository;
import com.bipros.ai.provider.OpenAiCompatibleProvider;
import com.bipros.ai.voice.SpeechToTextService;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.resource.domain.model.ProjectResource;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.repository.ProjectResourceRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates a single voice form-fill turn:
 * <ol>
 *   <li>Whisper-transcribe the audio.</li>
 *   <li>Load reference data (eligible supervisors, project activities, BOQ items) so the LLM
 *       can resolve free-text mentions to canonical UUIDs.</li>
 *   <li>Build a chat request with a system prompt + reference data + the form's current state +
 *       prior turns + the new transcript.</li>
 *   <li>Validate the structured response against the canonical lists, demoting any unresolvable
 *       reference to a follow-up question.</li>
 * </ol>
 *
 * <p>Reference-data lookups follow the same logic the FE pickers use: eligible supervisors are
 * active LABOR resources scoped to the project's pool (with a global fallback when the pool is
 * empty), activities are all rows for the project, BOQ items are the project's BOQ rows ordered
 * by item number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DprVoiceFillService {

  private static final String LABOR_TYPE_CODE = "MANPOWER";
  private static final int MAX_REFERENCE_LIST_SIZE = 200;

  private final SpeechToTextService speechToTextService;
  private final LlmProviderConfigRepository providerConfigRepository;
  private final OpenAiCompatibleProvider provider;
  private final DprVoiceFillSchema schemaBuilder;
  private final ObjectMapper objectMapper;

  private final ResourceRepository resourceRepository;
  private final ProjectResourceRepository projectResourceRepository;
  private final ActivityRepository activityRepository;
  private final BoqItemRepository boqItemRepository;

  public DprVoiceFillResponse fill(UUID projectId, MultipartFile audio, DprVoiceFillRequest request) {
    String filename = audio.getOriginalFilename() == null ? "audio.webm" : audio.getOriginalFilename();
    String mimeType = audio.getContentType() == null ? "audio/webm" : audio.getContentType();

    byte[] bytes;
    try {
      bytes = audio.getBytes();
    } catch (IOException e) {
      throw new BusinessRuleException("VOICE_AUDIO_READ", "Failed to read audio payload");
    }

    String transcript = speechToTextService.transcribe(bytes, filename, mimeType);
    log.info("[dpr voice-fill] project={} transcript=\"{}\"", projectId,
        transcript.length() > 200 ? transcript.substring(0, 200) + "…" : transcript);

    // Reference data is loaded fresh per call so a recently-pooled supervisor is visible
    // immediately. Volumes are small (<200 in practice), so caching isn't justified yet.
    ReferenceData refs = loadReferenceData(projectId);

    LlmProviderConfig cfg = providerConfigRepository.findByIsDefaultTrueAndIsActiveTrue()
        .orElseGet(() -> providerConfigRepository
            .findFirstByIsActiveTrueOrderByIsDefaultDescCreatedAtAsc()
            .orElseThrow(() -> new BusinessRuleException(
                "AI_NO_PROVIDER", "No active LLM provider configured")));

    LlmProvider.ChatRequest chatRequest = buildChatRequest(transcript, refs, request);
    LlmProvider.ChatResponse chatResponse;
    try {
      chatResponse = provider.chat(cfg, chatRequest);
    } catch (RuntimeException e) {
      log.error("[dpr voice-fill] LLM call failed", e);
      throw new BusinessRuleException("VOICE_LLM_FAILED", "AI form-fill failed: " + e.getMessage());
    }

    JsonNode root;
    try {
      root = objectMapper.readTree(chatResponse.content());
    } catch (IOException e) {
      throw new BusinessRuleException("VOICE_LLM_BAD_JSON",
          "AI returned an unparseable response. Try again or fill the form manually.");
    }

    return validateAndAssemble(transcript, root, refs);
  }

  // ─── prompt assembly ──────────────────────────────────────────────────────────

  private LlmProvider.ChatRequest buildChatRequest(
      String transcript, ReferenceData refs, DprVoiceFillRequest req) {
    List<LlmProvider.Message> messages = new ArrayList<>();
    messages.add(new LlmProvider.Message("system", systemPrompt()));
    messages.add(new LlmProvider.Message("system", referenceDataPrompt(refs)));
    messages.add(new LlmProvider.Message("system", currentStatePrompt(req.state())));

    if (req.history() != null) {
      for (DprVoiceTurn turn : req.history()) {
        if (turn.role() == null || turn.content() == null) continue;
        messages.add(new LlmProvider.Message(turn.role(), turn.content()));
      }
    }
    messages.add(new LlmProvider.Message("user", transcript));

    JsonNode responseFormat = schemaBuilder.buildSchema();
    return new LlmProvider.ChatRequest(
        messages,
        null,        // no tools — pure structured output
        1024,        // maxTokens
        0.1,         // low temperature: factual extraction, not creative writing
        45_000L,     // 45 s timeout
        responseFormat);
  }

  private String systemPrompt() {
    return """
        You fill construction Daily Progress Reports (DPRs) for a road / corridor project. Be terse.
        Extract only what the user actually said. Do not invent supervisor / activity / BOQ-item /
        resource-assignment values that aren't in the provided lists.

        Rules:
        - Output strictly conforms to the response_format JSON schema. Set fields you didn't hear to null.
        - Default reportDate to today, shift to DAY, safetyIncidentType to NONE, approvalStatus to DRAFT.
        - Convert spoken chainages like "145 plus 200" or "145+200" into metres (145200).
        - When the user names a supervisor / activity / BOQ item ambiguously (no exact match, multiple
          near matches), set the related id to null and put the clarification in followUpQuestion.
        - When a quantity is given without a matching unit, ask for the unit in followUpQuestion.
        - Manpower / Equipment / Material rows you return are APPENDED to the user's existing rows;
          never overwrite them. Skip rows the user didn't speak about.
        - resourceAssignmentId values must come from the provided assignment hints if any are listed;
          otherwise leave null and request clarification.
        - Set complete=true only when no follow-up is needed and you've captured every spoken fact.
        - assistantMessage is a short reply to the user (1-2 sentences) confirming what you heard
          plus the follow-up if any.
        """;
  }

  private String referenceDataPrompt(ReferenceData refs) {
    StringBuilder sb = new StringBuilder();
    sb.append("REFERENCE DATA for this project (use ONLY these for id resolution):\n\n");

    sb.append("Eligible supervisors (id — name [role]):\n");
    if (refs.supervisors().isEmpty()) {
      sb.append("  (none)\n");
    } else {
      for (SupervisorRef s : refs.supervisors()) {
        sb.append("  ").append(s.id()).append(" — ").append(s.name());
        if (s.roleName() != null) sb.append(" [").append(s.roleName()).append("]");
        sb.append('\n');
      }
    }

    sb.append("\nProject activities (id — code — name):\n");
    if (refs.activities().isEmpty()) {
      sb.append("  (none)\n");
    } else {
      for (ActivityRef a : refs.activities()) {
        sb.append("  ").append(a.id()).append(" — ")
            .append(a.code() == null ? "" : a.code()).append(" — ")
            .append(a.name()).append('\n');
      }
    }

    sb.append("\nBOQ items (itemNo — unit — description):\n");
    if (refs.boqItems().isEmpty()) {
      sb.append("  (none)\n");
    } else {
      for (BoqItemRef b : refs.boqItems()) {
        sb.append("  ").append(b.itemNo()).append(" — ")
            .append(b.unit() == null ? "" : b.unit()).append(" — ")
            .append(b.description()).append('\n');
      }
    }
    return sb.toString();
  }

  private String currentStatePrompt(JsonNode state) {
    return "CURRENT FORM STATE (do not duplicate fields the user already filled — only patch what "
        + "they newly mention):\n" + state.toPrettyString();
  }

  // ─── validation + assembly ────────────────────────────────────────────────────

  private DprVoiceFillResponse validateAndAssemble(String transcript, JsonNode root, ReferenceData refs) {
    JsonNode patch = root.path("patch");
    String followUp = nullableText(root.path("followUpQuestion"));
    boolean complete = root.path("complete").asBoolean(false);
    String assistantMessage = root.path("assistantMessage").asText("OK.");

    Set<UUID> validSupervisorIds = setOf(refs.supervisors(), s -> UUID.fromString(s.id()));
    Set<UUID> validActivityIds = setOf(refs.activities(), a -> UUID.fromString(a.id()));
    Set<String> validBoqItemNos = new HashSet<>();
    for (BoqItemRef b : refs.boqItems()) validBoqItemNos.add(b.itemNo());

    List<String> demoted = new ArrayList<>();

    if (patch instanceof ObjectNode obj) {
      // Supervisor: drop hallucinated id, leave name visible so the FE can show "needs review".
      JsonNode supervisorIdNode = obj.path("supervisorResourceId");
      if (!supervisorIdNode.isNull() && supervisorIdNode.isTextual()) {
        UUID parsed = tryParseUuid(supervisorIdNode.asText());
        if (parsed == null || !validSupervisorIds.contains(parsed)) {
          obj.putNull("supervisorResourceId");
          demoted.add("supervisor");
        }
      }
      JsonNode activityIdNode = obj.path("activityId");
      if (!activityIdNode.isNull() && activityIdNode.isTextual()) {
        UUID parsed = tryParseUuid(activityIdNode.asText());
        if (parsed == null || !validActivityIds.contains(parsed)) {
          obj.putNull("activityId");
          demoted.add("activity");
        }
      }
      JsonNode boqNode = obj.path("boqItemNo");
      if (!boqNode.isNull() && boqNode.isTextual() && !validBoqItemNos.contains(boqNode.asText())) {
        obj.putNull("boqItemNo");
        demoted.add("BOQ item");
      }
    }

    if (!demoted.isEmpty() && (followUp == null || followUp.isBlank())) {
      followUp = "I couldn't match the " + String.join(" / ", demoted) + " against the project's list. "
          + "Could you clarify?";
      complete = false;
    }

    DprVoiceTurn assistantTurn = new DprVoiceTurn("assistant", assistantMessage);
    List<DprVoiceFillResponse.PhotoCaption> photoCaptions = readPhotoCaptions(root.path("photoCaptions"));
    return new DprVoiceFillResponse(
        transcript, patch, photoCaptions, followUp, complete, assistantTurn);
  }

  private List<DprVoiceFillResponse.PhotoCaption> readPhotoCaptions(JsonNode node) {
    if (!node.isArray()) return List.of();
    List<DprVoiceFillResponse.PhotoCaption> out = new ArrayList<>();
    for (JsonNode item : node) {
      String id = item.path("photoId").asText(null);
      String caption = item.path("caption").asText(null);
      if (id != null && !id.isBlank() && caption != null) {
        out.add(new DprVoiceFillResponse.PhotoCaption(id, caption));
      }
    }
    return out;
  }

  private static String nullableText(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return null;
    String s = n.asText("");
    return s.isBlank() ? null : s;
  }

  private static UUID tryParseUuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static <T> Set<UUID> setOf(List<T> items, java.util.function.Function<T, UUID> idOf) {
    Set<UUID> set = new HashSet<>();
    for (T t : items) {
      try {
        set.add(idOf.apply(t));
      } catch (RuntimeException ignored) {
        // skip malformed ids
      }
    }
    return set;
  }

  // ─── reference-data loading ──────────────────────────────────────────────────

  private ReferenceData loadReferenceData(UUID projectId) {
    return new ReferenceData(
        loadSupervisors(projectId),
        loadActivities(projectId),
        loadBoqItems(projectId));
  }

  private List<SupervisorRef> loadSupervisors(UUID projectId) {
    // Mirror EligibleSupervisorsController: project-pool first, global fallback when empty.
    List<Resource> all = resourceRepository.findByResourceType_CodeAndStatus(
        LABOR_TYPE_CODE, ResourceStatus.ACTIVE);
    Set<UUID> pooled = new HashSet<>();
    for (ProjectResource pr : projectResourceRepository.findByProjectId(projectId)) {
      if (pr.getResourceId() != null) pooled.add(pr.getResourceId());
    }
    List<Resource> scoped = new ArrayList<>();
    for (Resource r : all) {
      if (pooled.contains(r.getId())) scoped.add(r);
    }
    List<Resource> source = scoped.isEmpty() ? all : scoped;

    List<SupervisorRef> out = new ArrayList<>(source.size());
    for (Resource r : source) {
      String roleName = r.getRole() == null ? null : r.getRole().getName();
      out.add(new SupervisorRef(r.getId().toString(), r.getName(), roleName));
    }
    out.sort(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT)));
    return cap(out);
  }

  private List<ActivityRef> loadActivities(UUID projectId) {
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    List<ActivityRef> out = new ArrayList<>(activities.size());
    for (Activity a : activities) {
      out.add(new ActivityRef(a.getId().toString(), a.getCode(), a.getName()));
    }
    out.sort(Comparator.comparing(a -> a.name().toLowerCase(Locale.ROOT)));
    return cap(out);
  }

  private List<BoqItemRef> loadBoqItems(UUID projectId) {
    List<BoqItem> items = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    List<BoqItemRef> out = new ArrayList<>(items.size());
    for (BoqItem b : items) {
      out.add(new BoqItemRef(b.getItemNo(), b.getDescription(), b.getUnit()));
    }
    return cap(out);
  }

  /** Cap reference lists to keep the prompt size predictable on huge projects. */
  private static <T> List<T> cap(List<T> in) {
    if (in.size() <= MAX_REFERENCE_LIST_SIZE) return in;
    return new ArrayList<>(in.subList(0, MAX_REFERENCE_LIST_SIZE));
  }

  // ─── records ─────────────────────────────────────────────────────────────────

  private record ReferenceData(
      List<SupervisorRef> supervisors,
      List<ActivityRef> activities,
      List<BoqItemRef> boqItems) {}

  private record SupervisorRef(String id, String name, String roleName) {}

  private record ActivityRef(String id, String code, String name) {}

  private record BoqItemRef(String itemNo, String description, String unit) {}
}
