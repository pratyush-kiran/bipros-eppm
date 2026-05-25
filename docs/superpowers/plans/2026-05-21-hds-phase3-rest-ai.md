# HDS Phase 3 — REST API + AI Tool Integration

> **Three parallel tracks.** Phases 1 and 2 must be green.

**Goal:** Backend exposes all admin + query REST endpoints. The `search_hds_standards` tool is registered in `bipros-ai`. The chat orchestrator deterministically routes to it when `hdsVersionIds` is non-empty. A curl against `/v1/ai/chat` with HDS scope returns a citation-bearing answer.

**Verify gate:**
```bash
(cd backend && mvn install -pl bipros-api -am -DskipTests -q)
(cd backend && mvn spring-boot:run -pl bipros-api) &
sleep 25
# Health
curl -fsS http://localhost:8080/swagger-ui/index.html >/dev/null && echo OK1
# An indexed version exists from manual seeding (Phase 5 covers automated)
ADMIN_TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.accessToken')
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/v1/hds/versions | jq '.data | length' >/dev/null && echo OK2
kill %1
```

---

## Track A — REST controllers + request/response DTOs

**Owns**: `bipros-hds/src/main/java/com/bipros/hds/api/**`. **Does NOT touch** `application/**`, `bipros-ai/**`.

### Task A.1 — Response DTOs (mapping helpers)

**Files:**
- Create: `.../api/dto/HdsDocumentResponse.java`
- Create: `.../api/dto/HdsVersionResponse.java`
- Create: `.../api/dto/HdsVersionDetailResponse.java`
- Create: `.../api/dto/HdsChunkResponse.java`
- Create: `.../api/dto/PresignedUrlResponse.java`
- Create: `.../api/dto/CreateHdsDocumentRequest.java`
- Create: `.../api/dto/UpdateHdsDocumentRequest.java`

- [ ] **Step 1: Records**

```java
// HdsDocumentResponse.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.enums.HdsDiscipline;
import java.time.Instant;
import java.util.UUID;

public record HdsDocumentResponse(UUID id, String title, String shortCode, HdsDiscipline discipline,
                                  String issuingAuthority, String country, String description,
                                  Instant createdAt, Instant updatedAt) {
    public static HdsDocumentResponse from(HdsDocument d) {
        return new HdsDocumentResponse(d.getId(), d.getTitle(), d.getShortCode(), d.getDiscipline(),
            d.getIssuingAuthority(), d.getCountry(), d.getDescription(),
            d.getCreatedAt(), d.getUpdatedAt());
    }
}
```
```java
// HdsVersionResponse.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HdsVersionResponse(UUID id, UUID hdsDocumentId, String versionLabel,
                                 Integer revisionYear, LocalDate effectiveDate,
                                 String fileName, Long fileSizeBytes, Integer pageCount,
                                 HdsVersionStatus status, Integer indexingProgressPct,
                                 Integer chunkCount, Instant uploadedAt, Instant indexedAt) {
    public static HdsVersionResponse from(HdsVersion v) {
        return new HdsVersionResponse(v.getId(), v.getHdsDocumentId(), v.getVersionLabel(),
            v.getRevisionYear(), v.getEffectiveDate(), v.getFileName(), v.getFileSizeBytes(),
            v.getPageCount(), v.getStatus(), v.getIndexingProgressPct(),
            v.getChunkCount(), v.getUploadedAt(), v.getIndexedAt());
    }
}
```
```java
// HdsVersionDetailResponse.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.HdsVersion;
public record HdsVersionDetailResponse(HdsVersionResponse version, String indexingError) {
    public static HdsVersionDetailResponse from(HdsVersion v) {
        return new HdsVersionDetailResponse(HdsVersionResponse.from(v), v.getIndexingError());
    }
}
```
```java
// HdsChunkResponse.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.enums.HdsChunkType;
import java.util.UUID;
public record HdsChunkResponse(UUID id, UUID versionId, String versionLabel,
                               int pageStart, int pageEnd, String sectionPath,
                               HdsChunkType chunkType, String content) {}
```
```java
// PresignedUrlResponse.java
package com.bipros.hds.api.dto;
import java.time.Instant;
public record PresignedUrlResponse(String url, Instant expiresAt) {}
```
```java
// CreateHdsDocumentRequest.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.enums.HdsDiscipline;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record CreateHdsDocumentRequest(@NotBlank @Size(max = 255) String title,
                                        @NotBlank @Size(max = 32) String shortCode,
                                        @NotNull HdsDiscipline discipline,
                                        @Size(max = 255) String issuingAuthority,
                                        @Size(max = 2) String country,
                                        String description) {}
```
```java
// UpdateHdsDocumentRequest.java
package com.bipros.hds.api.dto;
import com.bipros.hds.domain.enums.HdsDiscipline;
public record UpdateHdsDocumentRequest(String title, HdsDiscipline discipline,
                                       String issuingAuthority, String country, String description) {}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/api/dto
git commit -m "feat(hds): API DTOs (responses + create/update requests)"
```

### Task A.2 — Admin controller: documents

**Files:**
- Create: `.../api/admin/HdsDocumentAdminController.java`

- [ ] **Step 1: Controller**

```java
package com.bipros.hds.api.admin;

import com.bipros.common.api.ApiResponse;
import com.bipros.hds.api.dto.*;
import com.bipros.hds.application.library.HdsLibraryService;
import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.application.library.dto.UpdateHdsDocumentInput;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hds/admin/documents")
@RequiredArgsConstructor
public class HdsDocumentAdminController {

    private final HdsLibraryService library;

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.CREATE')")
    @PostMapping
    public ResponseEntity<ApiResponse<HdsDocumentResponse>> create(@Valid @RequestBody CreateHdsDocumentRequest req) {
        var doc = library.createDocument(new CreateHdsDocumentInput(
            req.title(), req.shortCode(), req.discipline(),
            req.issuingAuthority(), req.country(), req.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(HdsDocumentResponse.from(doc)));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<HdsDocumentResponse>>> list() {
        var docs = library.listDocuments().stream().map(HdsDocumentResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(docs));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.UPDATE')")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<HdsDocumentResponse>> update(@PathVariable UUID id,
                                                                    @RequestBody UpdateHdsDocumentRequest req) {
        var doc = library.updateDocument(id, new UpdateHdsDocumentInput(
            req.title(), req.discipline(), req.issuingAuthority(), req.country(), req.description()));
        return ResponseEntity.ok(ApiResponse.ok(HdsDocumentResponse.from(doc)));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.DELETE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        library.deleteDocument(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/api/admin/HdsDocumentAdminController.java
git commit -m "feat(hds): admin REST controller for HDS documents"
```

### Task A.3 — Admin controller: versions (multipart upload, retry, delete, SSE progress)

**Files:**
- Create: `.../api/admin/HdsVersionAdminController.java`

- [ ] **Step 1: Controller**

```java
package com.bipros.hds.api.admin;

import com.bipros.common.api.ApiResponse;
import com.bipros.common.security.SecurityUtil;  // adapt to actual util that returns the user UUID
import com.bipros.hds.api.dto.HdsVersionDetailResponse;
import com.bipros.hds.api.dto.HdsVersionResponse;
import com.bipros.hds.application.ingestion.ProgressStreamRegistry;
import com.bipros.hds.application.library.HdsLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hds/admin")
@RequiredArgsConstructor
public class HdsVersionAdminController {

    private final HdsLibraryService library;
    private final ProgressStreamRegistry progress;

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.CREATE')")
    @PostMapping(value = "/documents/{docId}/versions", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<HdsVersionResponse>> upload(
            @PathVariable UUID docId,
            @RequestParam("versionLabel") String versionLabel,
            @RequestParam(value = "revisionYear", required = false) Integer revisionYear,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID userId = SecurityUtil.currentUserId();
        try {
            var version = library.uploadVersion(docId, versionLabel, revisionYear,
                file.getInputStream(), file.getSize(), file.getOriginalFilename(), userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(HdsVersionResponse.from(version)));
        } catch (HdsLibraryService.DuplicateUploadException dup) {
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
}
```

> `SecurityUtil.currentUserId()` is shorthand — match the actual project utility. Likely `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` cast. Pull from an existing controller in `bipros-cost` or similar for the exact call.

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/api/admin/HdsVersionAdminController.java
git commit -m "feat(hds): admin REST controller for HDS versions (upload, retry, SSE, delete)"
```

### Task A.4 — Query-side controller (versions list, chunk fetch, presigned PDF)

**Files:**
- Create: `.../api/HdsQueryController.java`

- [ ] **Step 1: Controller**

```java
package com.bipros.hds.api;

import com.bipros.common.api.ApiResponse;
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
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hds")
@RequiredArgsConstructor
public class HdsQueryController {

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
        Duration ttl = Duration.ofMinutes(10);
        var url = storage.presignGet(v.getStorageKey(), ttl);
        return ResponseEntity.ok(ApiResponse.ok(
            new PresignedUrlResponse(url.toString(), Instant.now().plus(ttl))));
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/api/HdsQueryController.java
git commit -m "feat(hds): query REST controller (versions list, chunk fetch, presigned PDF)"
```

### Task A.5 — Controller smoke tests (`@WebMvcTest`)

**Files:**
- Create: `backend/bipros-hds/src/test/java/com/bipros/hds/api/admin/HdsDocumentAdminControllerTest.java`

- [ ] **Step 1: Test**

```java
package com.bipros.hds.api.admin;

import com.bipros.hds.application.library.HdsLibraryService;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.enums.HdsDiscipline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HdsDocumentAdminController.class)
class HdsDocumentAdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockBean HdsLibraryService library;

    @Test
    @WithMockUser(authorities = "HDS_LIBRARY.CREATE")
    void createsDocument() throws Exception {
        var doc = HdsDocument.builder().title("HDS V3").shortCode("HDS-V3").discipline(HdsDiscipline.HIGHWAY).build();
        doc.setId(UUID.randomUUID());
        when(library.createDocument(any())).thenReturn(doc);

        mvc.perform(post("/v1/hds/admin/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "title","HDS V3","shortCode","HDS-V3","discipline","HIGHWAY"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.shortCode").value("HDS-V3"));
    }
}
```

- [ ] **Step 2: Commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=HdsDocumentAdminControllerTest -q)
git add backend/bipros-hds/src/test/java/com/bipros/hds/api
git commit -m "test(hds): controller smoke test (document admin)"
```

---

## Track B — AI tool integration (`SearchHdsStandardsTool` + prompts)

**Owns**: `bipros-ai/src/main/java/com/bipros/ai/tool/hds/**`. **Does NOT touch** `bipros-hds/**` or `bipros-ai/chat/**` or `bipros-ai/orchestrator/**`.

### Task B.1 — `SearchHdsStandardsTool`

**Files:**
- Create: `backend/bipros-ai/src/main/java/com/bipros/ai/tool/hds/SearchHdsStandardsTool.java`

- [ ] **Step 1: Tool implementation**

```java
package com.bipros.ai.tool.hds;

import com.bipros.ai.tool.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.hds.application.retrieval.Citation;
import com.bipros.hds.application.retrieval.RetrievalAnswer;
import com.bipros.hds.application.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class SearchHdsStandardsTool implements Tool {

    private final RetrievalService retrieval;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String name() { return "search_hds_standards"; }

    @Override
    public String description() {
        return "Look up Highway Design Standard (HDS) information from the user-selected HDS document versions. " +
               "All factual claims will be grounded in cited chunks from those documents. Use when the user " +
               "has selected HDS scope and asks about engineering standards, dimensions, requirements, or " +
               "any normative content from highway design publications.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = om.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("question").put("type", "string").put("description", "The user question, possibly rephrased.");
        ObjectNode versions = props.putObject("selected_version_ids");
        versions.put("type", "array").put("description", "UUIDs of the HDS versions the user has selected.");
        versions.putObject("items").put("type", "string");
        props.putObject("max_rounds").put("type", "integer").put("description", "Max ReAct iteration rounds (default 2).");
        s.putArray("required").add("question").add("selected_version_ids");
        return s;
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String question = input.path("question").asText("").trim();
        if (question.isEmpty()) return ToolResult.error("Missing 'question' input");
        List<UUID> versionIds = new ArrayList<>();
        if (input.has("selected_version_ids") && input.get("selected_version_ids").isArray()) {
            input.get("selected_version_ids").forEach(n -> versionIds.add(UUID.fromString(n.asText())));
        }
        if (versionIds.isEmpty()) return ToolResult.error("selected_version_ids must contain at least one version UUID");

        int maxRounds = input.path("max_rounds").asInt(2);
        UUID userId = safeUuid(ctx.userId());
        UUID conversationId = safeUuid(ctx.conversationId());

        RetrievalAnswer answer = retrieval.answer(question, versionIds, maxRounds, userId, conversationId, null);

        // Build a ToolResult payload that the orchestrator + frontend understand
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", answer.answer());
        List<Map<String, Object>> cites = new ArrayList<>();
        for (Citation c : answer.citations()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("marker", c.marker());
            m.put("chunk_id", c.chunkId().toString());
            m.put("version_id", c.versionId().toString());
            m.put("version_label", c.versionLabel());
            m.put("section_path", c.sectionPath());
            m.put("page_start", c.pageStart());
            m.put("page_end", c.pageEnd());
            m.put("excerpt", c.excerpt());
            cites.add(m);
        }
        body.put("citations", cites);
        body.put("verifier_passed", answer.verifier().passed());
        body.put("metadata", answer.metadata());

        String summary = answer.answer();
        return ToolResult.ok(summary, body);
    }

    private UUID safeUuid(Object raw) {
        try { return raw == null ? null : UUID.fromString(raw.toString()); }
        catch (Exception e) { return null; }
    }
}
```

> **Adapt to existing `Tool` / `ToolResult` shapes** — the Phase 0 reference confirmed `name()`, `description()`, `inputSchema()`, `execute(JsonNode, AiContext)`, `isReadOnly()`. If `ToolResult.ok(summary, body)` differs from the project's signature, follow the same pattern as `AnalyzeCostTool` (see `backend/bipros-ai/src/main/java/com/bipros/ai/tool/AnalyzeCostTool.java`).

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-ai/src/main/java/com/bipros/ai/tool/hds/SearchHdsStandardsTool.java
git commit -m "feat(hds): SearchHdsStandardsTool — agentic HDS retrieval tool"
```

### Task B.2 — Wire `bipros-ai` ↔ `bipros-hds` Maven dependency

**Files:**
- Modify: `backend/bipros-ai/pom.xml`

- [ ] **Step 1: Add dep**

```xml
<dependency>
    <groupId>com.bipros</groupId>
    <artifactId>bipros-hds</artifactId>
</dependency>
```

- [ ] **Step 2: Verify**
```bash
(cd backend && mvn install -pl bipros-ai -am -DskipTests -q)
```

- [ ] **Step 3: Commit**
```bash
git add backend/bipros-ai/pom.xml
git commit -m "feat(hds): bipros-ai depends on bipros-hds (for RetrievalService bean)"
```

### Task B.3 — `LlmGateway` Spring bean (real, delegates to existing provider)

**Files:**
- Create: `backend/bipros-ai/src/main/java/com/bipros/ai/tool/hds/HdsLlmGatewayAdapter.java`

- [ ] **Step 1: Adapter**

```java
package com.bipros.ai.tool.hds;

import com.bipros.ai.provider.LlmProvider;
import com.bipros.ai.provider.LlmProviderConfig;
import com.bipros.hds.application.retrieval.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Bridges bipros-hds's LlmGateway interface to bipros-ai's existing LlmProvider.
 * Resolves the active provider config from the same source the chat orchestrator uses.
 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class HdsLlmGatewayAdapter implements LlmGateway {

    private final LlmProvider provider;       // existing bean
    // If there are multiple beans, qualify with @Qualifier("openAiCompatibleLlmProvider")

    @Override
    public String completeStructured(List<ChatMessage> messages, String responseFormatName) {
        var providerMsgs = messages.stream()
            .map(m -> new LlmProvider.Message(m.role(), m.content()))
            .collect(Collectors.toList());
        LlmProviderConfig cfg = resolveConfig();
        // Use structured/json mode if the provider supports it; otherwise plain completion + caller parses JSON.
        return provider.complete(providerMsgs, cfg);   // adapt method name if needed
    }

    @Override
    public String completeStreaming(List<ChatMessage> messages, StreamCallback onToken) {
        var providerMsgs = messages.stream()
            .map(m -> new LlmProvider.Message(m.role(), m.content()))
            .collect(Collectors.toList());
        LlmProviderConfig cfg = resolveConfig();
        StringBuilder sb = new StringBuilder();
        provider.stream(providerMsgs, cfg, token -> {
            sb.append(token);
            if (onToken != null) onToken.onToken(token);
        });
        return sb.toString();
    }

    private LlmProviderConfig resolveConfig() {
        // Replicate the same lookup ChatController uses (resolveConfig method).
        // If a service like ProviderConfigService exists, inject it instead.
        return provider.defaultConfig();
    }
}
```

> The exact method names `complete`, `stream`, `defaultConfig` may differ — check `backend/bipros-ai/src/main/java/com/bipros/ai/provider/LlmProvider.java`. Adapt this adapter to the real interface.

- [ ] **Step 2: Commit**
```bash
(cd backend && mvn install -pl bipros-ai -am -DskipTests -q)
git add backend/bipros-ai/src/main/java/com/bipros/ai/tool/hds/HdsLlmGatewayAdapter.java
git commit -m "feat(hds): LlmGateway adapter — bridges hds RetrievalService to existing LlmProvider"
```

### Task B.4 — Tool unit test (stubs the retrieval service)

**Files:**
- Create: `backend/bipros-ai/src/test/java/com/bipros/ai/tool/hds/SearchHdsStandardsToolTest.java`

- [ ] **Step 1: Test**

```java
package com.bipros.ai.tool.hds;

import com.bipros.ai.tool.AiContext;
import com.bipros.ai.tool.ToolResult;
import com.bipros.hds.application.retrieval.Citation;
import com.bipros.hds.application.retrieval.RetrievalAnswer;
import com.bipros.hds.application.retrieval.RetrievalService;
import com.bipros.hds.application.retrieval.VerifyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchHdsStandardsToolTest {

    @Test
    void returnsAnswerAndCitations() throws Exception {
        var retrieval = mock(RetrievalService.class);
        UUID chunkId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(retrieval.answer(anyString(), any(), anyInt(), any(), any(), any()))
            .thenReturn(new RetrievalAnswer(
                "Answer with citation [c1].",
                List.of(new Citation("c1", chunkId, versionId, "HDS-V3 Rev 1",
                    "Vol 3 > 4.3", 87, 87, "excerpt")),
                new VerifyResult(true, List.of()),
                Map.of("duration_ms", 1234)));

        var tool = new SearchHdsStandardsTool(retrieval);
        var input = new ObjectMapper().readTree("""
            {"question":"shoulder width","selected_version_ids":["%s"],"max_rounds":1}
            """.formatted(versionId));
        ToolResult result = tool.execute(input, mock(AiContext.class));

        assertThat(result.summary()).contains("citation [c1]");
        // body should contain citations array — exact shape depends on ToolResult; assert via ObjectMapper round-trip
    }
}
```

- [ ] **Step 2: Commit**
```bash
(cd backend && mvn test -pl bipros-ai -Dtest=SearchHdsStandardsToolTest -q)
git add backend/bipros-ai/src/test/java/com/bipros/ai/tool/hds/SearchHdsStandardsToolTest.java
git commit -m "test(hds): SearchHdsStandardsTool unit test with mocked retrieval"
```

---

## Track C — Conversation field + Orchestrator deterministic routing + ChatRequest

**Owns**: `backend/bipros-ai/src/main/java/com/bipros/ai/chat/AiConversation.java`, `backend/bipros-ai/src/main/java/com/bipros/ai/chat/ChatRequest.java`, `backend/bipros-ai/src/main/java/com/bipros/ai/orchestrator/AiOrchestrator.java`. **Does NOT touch** `bipros-hds/**` or `bipros-ai/tool/**`.

### Task C.1 — `AiConversation` gets `hds_version_ids` JSONB

**Files:**
- Modify: `backend/bipros-ai/src/main/java/com/bipros/ai/chat/AiConversation.java`

- [ ] **Step 1: Add field**

Add to the entity (matching existing field style, after `private Instant deletedAt;`):
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "hds_version_ids", columnDefinition = "jsonb")
private List<String> hdsVersionIds;
```
Add imports:
```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;
```

- [ ] **Step 2: Commit**
```bash
(cd backend && mvn install -pl bipros-ai -am -DskipTests -q)
git add backend/bipros-ai/src/main/java/com/bipros/ai/chat/AiConversation.java
git commit -m "feat(hds): AiConversation gains hds_version_ids JSONB column"
```

### Task C.2 — `ChatRequest` adds `hdsVersionIds`

**Files:**
- Modify: `backend/bipros-ai/src/main/java/com/bipros/ai/chat/ChatRequest.java` (or wherever the DTO lives — may be a record under `dto/`)

- [ ] **Step 1: Add field**

If the DTO is a record:
```java
public record ChatRequest(UUID projectId, UUID conversationId, String module,
                          String message, String imageUrl,
                          List<String> hdsVersionIds) { }
```
If it's a class, add `private List<String> hdsVersionIds;` plus getter/setter.

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-ai/src/main/java/com/bipros/ai/chat/ChatRequest.java
git commit -m "feat(hds): ChatRequest gains optional hdsVersionIds field"
```

### Task C.3 — `AiOrchestrator` deterministic-routing branch

**Files:**
- Modify: `backend/bipros-ai/src/main/java/com/bipros/ai/orchestrator/AiOrchestrator.java`
- Modify: `backend/bipros-ai/src/main/java/com/bipros/ai/chat/ChatController.java` — pass `hdsVersionIds` through

- [ ] **Step 1: Add HDS-scope direct invocation**

In `AiOrchestrator.handle(...)`:
- Read `ctx.hdsVersionIds()` (add accessor to `AiContext`) OR pass `hdsVersionIds` as a separate parameter.
- If list is non-empty:
  1. Skip the LLM-driven tool-selection loop.
  2. Build the `search_hds_standards` tool input JSON:
     ```json
     {"question": "<user message>", "selected_version_ids": [...], "max_rounds": 2}
     ```
  3. Call `searchHdsStandardsTool.execute(input, ctx)` directly.
  4. Stream the `summary` (answer text) via sink as `tool_progress` + token events.
  5. Emit a final `tool_complete` event with the citations payload.

Suggested method signature change (additive, default = empty list so existing callers stay compatible):
```java
public Flux<ChatEvent> handle(String userMessage, String imageUrl, List<LlmProvider.Message> history,
                               AiContext ctx, LlmProvider provider, LlmProviderConfig config) {
    List<UUID> hdsScope = ctx.hdsVersionIds();   // new accessor
    if (hdsScope != null && !hdsScope.isEmpty()) {
        return handleHdsDirect(userMessage, hdsScope, ctx);
    }
    // ...existing path
}

private Flux<ChatEvent> handleHdsDirect(String userMessage, List<UUID> versionIds, AiContext ctx) {
    return Flux.<ChatEvent>create(sink -> {
        sink.next(new ChatEvent("tool_progress", Map.of("label", "search_hds_standards: retrieving")));
        // ... build JSON, call tool, stream answer
    });
}
```

> The exact event shape (`ChatEvent` field names) and `AiContext` accessor pattern depend on the existing class — read both before editing. Keep the existing event vocabulary intact; the frontend already knows it.

- [ ] **Step 2: Pass `hdsVersionIds` through `ChatController`**

In `chat()` and `chatStream()`:
```java
AiContext ctx = contextResolver.resolve(request.projectId(), request.module(),
    request.hdsVersionIds() == null ? List.of() : request.hdsVersionIds().stream()
        .map(UUID::fromString).toList());
```
Update `AiContextResolver` to accept and store the list.

- [ ] **Step 3: Persist on the conversation**

After resolving `ctx`, if `request.hdsVersionIds()` is non-null:
```java
conv.setHdsVersionIds(request.hdsVersionIds());
conversationService.save(conv);
```
This makes scope conversation-scoped — subsequent messages without `hdsVersionIds` in the request fall back to the conversation's stored scope. Implement this lookup in `AiContextResolver`: prefer request → fall back to conversation.

- [ ] **Step 4: Integration test of the deterministic-routing branch**

Create `backend/bipros-api/src/test/java/com/bipros/api/integration/HdsChatRoutingIT.java`:
```java
package com.bipros.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class HdsChatRoutingIT {

    @Autowired MockMvc mvc;
    // Mock the RetrievalService bean to return a canned answer + citations.

    @Test
    void chatWithHdsScopeInvokesSearchTool() throws Exception {
        // Auth, then POST /v1/ai/chat with hdsVersionIds populated.
        // Assert response contains the canned answer string and citations array.
    }
}
```

Fill in the auth + JSON body to match the existing chat integration test style (see other IT tests in `bipros-api/src/test`).

- [ ] **Step 5: Commit**
```bash
(cd backend && mvn install -pl bipros-api -am -q)
git add backend/bipros-ai/src/main/java/com/bipros/ai/orchestrator/AiOrchestrator.java \
        backend/bipros-ai/src/main/java/com/bipros/ai/chat/ChatController.java \
        backend/bipros-ai/src/main/java/com/bipros/ai/context/AiContext.java \
        backend/bipros-ai/src/main/java/com/bipros/ai/context/AiContextResolver.java \
        backend/bipros-api/src/test/java/com/bipros/api/integration/HdsChatRoutingIT.java
git commit -m "feat(hds): orchestrator deterministic routing when hdsVersionIds present"
```

---

## Phase 3 verify gate

```bash
(cd backend && mvn install -pl bipros-api -am -q)
(cd backend && mvn spring-boot:run -pl bipros-api -q) &
sleep 25

ADMIN_TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.accessToken')

# Hit the new HDS endpoints (will be empty until Phase 5 uploads something)
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/v1/hds/admin/documents | jq '.success'
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/v1/hds/versions | jq '.success'

# Verify the deterministic-route is at least wired (with no versions selected → falls back to existing tools)
curl -fsS -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"hello","module":"general","projectId":null}' \
  http://localhost:8080/v1/ai/chat | jq '.success'

kill %1
```

All three `success: true` outputs indicate Phase 3 is wired. The semantic correctness (i.e., HDS scope → HDS tool) is exercised in Phase 5.
