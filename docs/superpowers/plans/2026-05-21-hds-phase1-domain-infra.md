# HDS Phase 1 — Domain & Infrastructure Clients

> **Three parallel tracks.** Each agent owns the directories listed under its track. No two tracks touch the same file.

**Goal:** All domain entities/repos compile and pass unit tests; infra clients (Docling HTTP, MinIO S3, embeddings, reranker) compile with mock-based unit tests.

**Verify gate (run after all three tracks complete):**
```bash
(cd backend && mvn test -pl bipros-hds -q)
```
Expected: BUILD SUCCESS, tests green.

---

## Track A — Domain layer

**Owns**: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/**`, related test files.

### Task A.1 — Enums

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/enums/HdsDiscipline.java`
- Create: `.../HdsVersionStatus.java`
- Create: `.../HdsIngestionStage.java`
- Create: `.../HdsChunkType.java`

- [ ] **Step 1: Write enums**

```java
// HdsDiscipline.java
package com.bipros.hds.domain.enums;

public enum HdsDiscipline {
    HIGHWAY, BRIDGE, GEOTECH, PAVEMENT, TRAFFIC, DRAINAGE, OTHER
}
```
```java
// HdsVersionStatus.java
package com.bipros.hds.domain.enums;

public enum HdsVersionStatus {
    PENDING, PARSING, CHUNKING, EMBEDDING, INDEXED, FAILED
}
```
```java
// HdsIngestionStage.java
package com.bipros.hds.domain.enums;

public enum HdsIngestionStage {
    PARSING, CHUNKING, EMBEDDING, INDEXING, COMPLETE, FAILED
}
```
```java
// HdsChunkType.java
package com.bipros.hds.domain.enums;

public enum HdsChunkType {
    TEXT, TABLE, FIGURE_CAPTION, FORMULA, LIST_ITEM
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/enums
git commit -m "feat(hds): domain enums (discipline, status, stage, chunk type)"
```

### Task A.2 — `HdsDocument` entity

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsDocument.java`

- [ ] **Step 1: Write entity**

```java
package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsDiscipline;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "hds_document", schema = "hds",
       uniqueConstraints = @UniqueConstraint(name = "uk_hds_document_short_code", columnNames = "short_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsDocument extends BaseEntity {

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "discipline", nullable = false, length = 32)
    private HdsDiscipline discipline;

    @Column(name = "issuing_authority", length = 255)
    private String issuingAuthority;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsDocument.java
git commit -m "feat(hds): HdsDocument entity"
```

### Task A.3 — `HdsVersion` entity

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsVersion.java`

- [ ] **Step 1: Write entity**

```java
package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hds_version", schema = "hds",
       uniqueConstraints = {
         @UniqueConstraint(name = "uk_hds_version_doc_label", columnNames = {"hds_document_id", "version_label"}),
         @UniqueConstraint(name = "uk_hds_version_sha", columnNames = "file_sha256")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsVersion extends BaseEntity {

    @Column(name = "hds_document_id", nullable = false)
    private UUID hdsDocumentId;

    @Column(name = "version_label", nullable = false, length = 64)
    private String versionLabel;

    @Column(name = "revision_year")
    private Integer revisionYear;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private HdsVersionStatus status = HdsVersionStatus.PENDING;

    @Column(name = "indexing_progress_pct", nullable = false)
    @Builder.Default
    private Integer indexingProgressPct = 0;

    @Column(name = "indexing_error", columnDefinition = "text")
    private String indexingError;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsVersion.java
git commit -m "feat(hds): HdsVersion entity"
```

### Task A.4 — `HdsChunk` entity (pgvector column)

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsChunk.java`

- [ ] **Step 1: Write entity**

The `embedding` and `tsv` columns are typed as `vector(1536)` and `tsvector` respectively. JPA can't load them directly; we mark `embedding` insertable=false/updatable=false here because writes go through the native repo (raw SQL) for performance. `tsv` is generated by Postgres.

```java
package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsChunkType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "hds_chunk", schema = "hds",
       indexes = {
         @Index(name = "idx_hds_chunk_version_idx", columnList = "hds_version_id, chunk_index"),
         @Index(name = "idx_hds_chunk_section_path", columnList = "section_path")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsChunk extends BaseEntity {

    @Column(name = "hds_version_id", nullable = false)
    private UUID hdsVersionId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "page_start", nullable = false)
    private Integer pageStart;

    @Column(name = "page_end", nullable = false)
    private Integer pageEnd;

    @Column(name = "section_path", nullable = false, columnDefinition = "text")
    private String sectionPath;

    @Column(name = "section_number", length = 32)
    private String sectionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false, length = 16)
    private HdsChunkType chunkType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_tokens")
    private Integer contentTokens;

    /**
     * Note: embedding is written via raw SQL (HybridSearchRepository / native insert),
     * not by JPA, because pgvector type binding is handled by the pgvector-java driver.
     * Keep this field for read-time reflection if needed; mark non-insertable/updatable.
     */
    @Column(name = "embedding", insertable = false, updatable = false, columnDefinition = "vector(1536)")
    private String embeddingRaw;
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsChunk.java
git commit -m "feat(hds): HdsChunk entity (pgvector column non-managed)"
```

### Task A.5 — `HdsIngestionJob` and `HdsQueryLog` entities

**Files:**
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsIngestionJob.java`
- Create: `backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsQueryLog.java`

- [ ] **Step 1: Write `HdsIngestionJob`**

```java
package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hds_ingestion_job", schema = "hds")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsIngestionJob extends BaseEntity {

    @Column(name = "hds_version_id", nullable = false)
    private UUID hdsVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private HdsIngestionStage stage;

    @Column(name = "progress_pct", nullable = false)
    @Builder.Default
    private Integer progressPct = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "worker_id", length = 64)
    private String workerId;
}
```

- [ ] **Step 2: Write `HdsQueryLog`**

```java
package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "hds_query_log", schema = "hds",
       indexes = @Index(name = "idx_hds_query_log_user_created", columnList = "user_id, created_at DESC"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsQueryLog extends BaseEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "query_text", columnDefinition = "text", nullable = false)
    private String queryText;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "selected_version_ids", columnDefinition = "uuid[]")
    private UUID[] selectedVersionIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "retrieved_chunk_ids", columnDefinition = "uuid[]")
    private UUID[] retrievedChunkIds;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb")
    private List<Map<String, Object>> citations;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage", columnDefinition = "jsonb")
    private Map<String, Object> tokenUsage;

    @Column(name = "verifier_passed")
    private Boolean verifierPassed;

    @Column(name = "rounds")
    private Integer rounds;
}
```

> If `io.hypersistence.utils` isn't in the BOM, fall back to plain `@Column(name = "citations", columnDefinition = "jsonb")` with `@JdbcTypeCode(SqlTypes.JSON)` only — Hibernate 6.x handles JSON natively for `Map`/`List`. Drop the `hypersistence-utils` import if unused.

- [ ] **Step 3: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsIngestionJob.java \
        backend/bipros-hds/src/main/java/com/bipros/hds/domain/HdsQueryLog.java
git commit -m "feat(hds): HdsIngestionJob + HdsQueryLog entities"
```

### Task A.6 — Repositories

**Files:**
- Create: `.../domain/repo/HdsDocumentRepository.java`
- Create: `.../domain/repo/HdsVersionRepository.java`
- Create: `.../domain/repo/HdsChunkRepository.java`
- Create: `.../domain/repo/HdsIngestionJobRepository.java`
- Create: `.../domain/repo/HdsQueryLogRepository.java`

- [ ] **Step 1: Write all 5 repos**

```java
// HdsDocumentRepository.java
package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsDocumentRepository extends JpaRepository<HdsDocument, UUID> {
    Optional<HdsDocument> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
}
```
```java
// HdsVersionRepository.java
package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsVersionRepository extends JpaRepository<HdsVersion, UUID> {
    List<HdsVersion> findByHdsDocumentIdOrderByRevisionYearDesc(UUID hdsDocumentId);
    List<HdsVersion> findByStatusOrderByUploadedAtAsc(HdsVersionStatus status);
    List<HdsVersion> findByStatus(HdsVersionStatus status);
    Optional<HdsVersion> findByFileSha256(String sha256);
}
```
```java
// HdsChunkRepository.java
package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HdsChunkRepository extends JpaRepository<HdsChunk, UUID> {
    long countByHdsVersionId(UUID hdsVersionId);
    void deleteByHdsVersionId(UUID hdsVersionId);
}
```
```java
// HdsIngestionJobRepository.java
package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsIngestionJobRepository extends JpaRepository<HdsIngestionJob, UUID> {

    Optional<HdsIngestionJob> findFirstByStageInOrderByCreatedAtAsc(List<HdsIngestionStage> stages);

    Optional<HdsIngestionJob> findByHdsVersionId(UUID hdsVersionId);

    @Modifying
    @Query("UPDATE HdsIngestionJob j SET j.lastHeartbeatAt = :ts WHERE j.id = :id")
    int touchHeartbeat(@Param("id") UUID id, @Param("ts") Instant ts);

    @Query("SELECT j FROM HdsIngestionJob j " +
           "WHERE j.stage IN (com.bipros.hds.domain.enums.HdsIngestionStage.PARSING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.CHUNKING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.EMBEDDING, " +
           "                  com.bipros.hds.domain.enums.HdsIngestionStage.INDEXING) " +
           "AND j.lastHeartbeatAt < :cutoff")
    List<HdsIngestionJob> findStaleJobs(@Param("cutoff") Instant cutoff);
}
```
```java
// HdsQueryLogRepository.java
package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsQueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HdsQueryLogRepository extends JpaRepository<HdsQueryLog, UUID> {}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/domain/repo
git commit -m "feat(hds): JPA repositories for hds entities"
```

### Task A.7 — Entity smoke test

**Files:**
- Create: `backend/bipros-hds/src/test/java/com/bipros/hds/domain/HdsEntitySmokeTest.java`

- [ ] **Step 1: Write the test**

```java
package com.bipros.hds.domain;

import com.bipros.hds.domain.enums.HdsDiscipline;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsDocumentRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=hds",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HdsEntitySmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("init-hds-test-schema.sql");

    @Autowired HdsDocumentRepository docRepo;
    @Autowired HdsVersionRepository verRepo;

    @Test
    void persistsDocumentAndVersion() {
        var doc = HdsDocument.builder()
            .title("HDS Vol 3")
            .shortCode("HDS-V3")
            .discipline(HdsDiscipline.HIGHWAY)
            .build();
        doc = docRepo.save(doc);

        var ver = HdsVersion.builder()
            .hdsDocumentId(doc.getId())
            .versionLabel("Rev 2.1")
            .revisionYear(2024)
            .status(HdsVersionStatus.PENDING)
            .fileSha256("a".repeat(64))
            .build();
        ver = verRepo.save(ver);

        assertThat(docRepo.findByShortCode("HDS-V3")).isPresent();
        assertThat(verRepo.findByHdsDocumentIdOrderByRevisionYearDesc(doc.getId()))
            .singleElement()
            .satisfies(v -> assertThat(v.getStatus()).isEqualTo(HdsVersionStatus.PENDING));
    }
}
```

- [ ] **Step 2: Create test init script**

Create `backend/bipros-hds/src/test/resources/init-hds-test-schema.sql`:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS hds;
```

- [ ] **Step 3: Run**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=HdsEntitySmokeTest -q)
```
Expected: BUILD SUCCESS. Testcontainers pulls `pgvector/pgvector:pg17` once.

- [ ] **Step 4: Commit**
```bash
git add backend/bipros-hds/src/test
git commit -m "test(hds): entity smoke test with pgvector testcontainer"
```

---

## Track B — Infrastructure clients (Docling, MinIO, Embedding, Reranker)

**Owns**: `backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/{docling,storage,embedding,reranker}/**`, related test files. **Does NOT touch** `domain/**` or `infrastructure/retrieval/**`.

### Task B.1 — Docling HTTP client

**Files:**
- Create: `.../infrastructure/docling/DoclingClient.java`
- Create: `.../infrastructure/docling/dto/DoclingResponse.java`
- Create: `.../infrastructure/docling/dto/DoclingBlock.java`

- [ ] **Step 1: Response DTOs**

```java
// DoclingResponse.java
package com.bipros.hds.infrastructure.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingResponse {
    private String status;
    private Integer pages;
    private List<DoclingBlock> blocks;
}
```
```java
// DoclingBlock.java
package com.bipros.hds.infrastructure.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingBlock {
    /** "heading", "paragraph", "table", "figure", "list_item", etc. */
    private String type;
    private Integer level;          // for headings
    private Integer page;
    private String text;            // for paragraph / list_item / figure caption
    private String markdown;        // for table (markdown table dump)
    private String sectionNumber;   // best-effort, e.g. "4.3.2"
}
```

- [ ] **Step 2: Client**

```java
package com.bipros.hds.infrastructure.docling;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class DoclingClient {

    private final HdsProperties props;
    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                .baseUrl(props.getDocling().getUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))  // 64 MB JSON ceiling
                .build();
        }
        return webClient;
    }

    /**
     * Synchronous: blocks until Docling returns the parsed structure.
     * For 1GB PDFs this may take 15–30 minutes — the caller (IngestionWorker) is on a long-lived thread.
     */
    public DoclingResponse parse(byte[] pdfBytes, String fileName) {
        log.info("Submitting PDF to Docling: name={}, size={} bytes", fileName, pdfBytes.length);
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("file", new ByteArrayResource(pdfBytes) {
            @Override public String getFilename() { return fileName; }
        }).contentType(MediaType.APPLICATION_PDF);

        Duration timeout = Duration.ofMinutes(props.getDocling().getTimeoutMinutes());
        DoclingResponse resp = client().post()
            .uri("/v1/convert")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(mb.build()))
            .retrieve()
            .bodyToMono(DoclingResponse.class)
            .block(timeout);

        if (resp == null) {
            throw new IllegalStateException("Docling returned null response");
        }
        log.info("Docling parse complete: pages={}, blocks={}",
            resp.getPages(), resp.getBlocks() == null ? 0 : resp.getBlocks().size());
        return resp;
    }
}
```

> **NB on the Docling-serve contract**: if the real `/v1/convert` returns a different JSON shape, adjust DTOs accordingly. The chunker in Phase 2 treats blocks as a flat list with `type`/`level`/`page`/`text|markdown` fields. Adapter logic for divergent shapes goes here.

- [ ] **Step 3: Test (mocked WebClient)**

Create `backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/docling/DoclingClientTest.java`:
```java
package com.bipros.hds.infrastructure.docling;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DoclingClientTest {

    MockWebServer server;
    DoclingClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        HdsProperties props = new HdsProperties();
        props.getDocling().setUrl(server.url("/").toString().replaceAll("/$",""));
        props.getDocling().setTimeoutMinutes(1);
        client = new DoclingClient(props);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void parsesResponse() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {"status":"ok","pages":3,"blocks":[
                  {"type":"heading","level":1,"page":1,"text":"Vol 3"},
                  {"type":"paragraph","page":1,"text":"intro text"}
                ]}
                """));

        DoclingResponse resp = client.parse(new byte[]{1,2,3}, "test.pdf");

        assertThat(resp.getStatus()).isEqualTo("ok");
        assertThat(resp.getPages()).isEqualTo(3);
        assertThat(resp.getBlocks()).hasSize(2);
        assertThat(resp.getBlocks().get(0).getType()).isEqualTo("heading");
    }
}
```

Add to `bipros-hds/pom.xml` test scope (if not already in parent BOM):
```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>mockwebserver</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=DoclingClientTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/docling \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/docling \
        backend/bipros-hds/pom.xml
git commit -m "feat(hds): Docling HTTP client + DTOs + mock test"
```

### Task B.2 — MinIO storage service (AWS S3 SDK v2)

**Files:**
- Create: `.../infrastructure/storage/MinioHdsStorageService.java`
- Create: `.../infrastructure/storage/HdsStorageService.java` (interface)
- Create: `.../infrastructure/storage/ShaInputStream.java` (utility: tee for SHA-256)

- [ ] **Step 1: Interface**

```java
package com.bipros.hds.infrastructure.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

public interface HdsStorageService {
    /** Uploads via streaming multipart. Returns the storage key. */
    UploadResult upload(InputStream input, long contentLength, String versionId, String fileName);

    /** Presigned GET URL for the version's PDF, valid for the given duration. */
    URL presignGet(String storageKey, Duration ttl);

    InputStream download(String storageKey);

    void delete(String storageKey);

    record UploadResult(String storageKey, String sha256, long size) {}
}
```

- [ ] **Step 2: Implementation**

```java
package com.bipros.hds.infrastructure.storage;

import com.bipros.hds.config.HdsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinioHdsStorageService implements HdsStorageService {

    private final HdsProperties props;
    private S3Client s3;
    private S3Presigner presigner;

    @PostConstruct
    void init() {
        var creds = AwsBasicCredentials.create(props.getStorage().getAccessKey(), props.getStorage().getSecretKey());
        var region = Region.of(props.getStorage().getRegion());
        s3 = S3Client.builder()
            .endpointOverride(URI.create(props.getStorage().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .region(region)
            .forcePathStyle(true)
            .build();
        presigner = S3Presigner.builder()
            .endpointOverride(URI.create(props.getStorage().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .region(region)
            .build();
        // Ensure bucket exists (idempotent — Phase 0 init script also handles this)
        try { s3.headBucket(HeadBucketRequest.builder().bucket(props.getStorage().getBucket()).build()); }
        catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.getStorage().getBucket()).build());
        }
    }

    @Override
    public UploadResult upload(InputStream input, long contentLength, String versionId, String fileName) {
        String key = "hds/" + versionId + "/" + sanitize(fileName);
        String bucket = props.getStorage().getBucket();
        long partSize = props.getStorage().getMultipartPartSizeMb() * 1024L * 1024L;

        try (var shaStream = new ShaInputStream(input)) {
            CreateMultipartUploadResponse mpu = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());

            List<CompletedPart> parts = new ArrayList<>();
            byte[] buf = new byte[(int) partSize];
            int partNumber = 1;
            int read;
            long totalRead = 0;

            while ((read = readFully(shaStream, buf)) > 0) {
                byte[] payload = read == buf.length ? buf : java.util.Arrays.copyOf(buf, read);
                var partResp = s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket).key(key)
                        .uploadId(mpu.uploadId())
                        .partNumber(partNumber)
                        .contentLength((long) read)
                        .build(),
                    RequestBody.fromInputStream(new ByteArrayInputStream(payload), read));
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResp.eTag()).build());
                totalRead += read;
                partNumber++;
            }

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key)
                .uploadId(mpu.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());

            return new UploadResult(key, shaStream.hexSha256(), totalRead);
        } catch (IOException e) {
            throw new IllegalStateException("MinIO upload failed", e);
        }
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    @Override
    public URL presignGet(String storageKey, Duration ttl) {
        var req = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(GetObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build())
            .build();
        return presigner.presignGetObject(req).url();
    }

    @Override
    public InputStream download(String storageKey) {
        return s3.getObject(
            GetObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build(),
            ResponseTransformer.toInputStream());
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build());
    }

    private String sanitize(String n) {
        return n == null ? "file.pdf" : n.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
```

- [ ] **Step 3: SHA-tee stream utility**

```java
package com.bipros.hds.infrastructure.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ShaInputStream extends FilterInputStream {
    private final MessageDigest md;

    public ShaInputStream(InputStream in) {
        super(in);
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) md.update((byte) b);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int r = super.read(b, off, len);
        if (r > 0) md.update(b, off, r);
        return r;
    }

    public String hexSha256() {
        return HexFormat.of().formatHex(md.digest());
    }
}
```

- [ ] **Step 4: Smoke test (uses live MinIO from compose)**

Create `backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/storage/MinioHdsStorageServiceIT.java`:
```java
package com.bipros.hds.infrastructure.storage;

import com.bipros.hds.config.HdsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "HDS_RUN_MINIO_IT", matches = "true")
class MinioHdsStorageServiceIT {

    @Test
    void roundTripsBytes() {
        HdsProperties props = new HdsProperties();
        props.getStorage().setBucket("hds");
        props.getStorage().setEndpoint("http://localhost:9000");
        props.getStorage().setAccessKey("minio");
        props.getStorage().setSecretKey("minio123");
        props.getStorage().setRegion("us-east-1");
        props.getStorage().setMultipartPartSizeMb(5);

        MinioHdsStorageService svc = new MinioHdsStorageService(props);
        svc.init();

        byte[] payload = "hello hds".getBytes();
        var result = svc.upload(new ByteArrayInputStream(payload), payload.length, "test-version-1", "x.pdf");
        assertThat(result.storageKey()).contains("test-version-1");
        assertThat(result.sha256()).hasSize(64);

        byte[] back = svc.download(result.storageKey()).readAllBytes();
        assertThat(back).isEqualTo(payload);

        var url = svc.presignGet(result.storageKey(), Duration.ofMinutes(5));
        assertThat(url.toString()).contains("X-Amz-Signature");

        svc.delete(result.storageKey());
    }
}
```

Run only with live MinIO:
```bash
HDS_RUN_MINIO_IT=true (cd backend && mvn test -pl bipros-hds -Dtest=MinioHdsStorageServiceIT -q)
```

- [ ] **Step 5: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/storage \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/storage
git commit -m "feat(hds): MinIO storage service (multipart streamed upload + SHA tee)"
```

### Task B.3 — Embedding client (delegates to OpenAI via existing provider key)

**Files:**
- Create: `.../infrastructure/embedding/EmbeddingClient.java`
- Create: `.../infrastructure/embedding/OpenAiEmbeddingClient.java`

> The existing `bipros-ai` module owns the encrypted-API-key infrastructure (`LlmProviderConfig`). For Phase 1, we expose a clean interface; the OpenAI implementation reads the key via the same mechanism. If `bipros-ai` doesn't yet have a `ProviderKeyService` we can pull from, the Phase 2 implementer can adapt — Track B's job is to define the interface and a working OpenAI client.

- [ ] **Step 1: Interface**

```java
package com.bipros.hds.infrastructure.embedding;

import java.util.List;

public interface EmbeddingClient {
    /** Returns one float[] per input string, in the same order. dim() floats each. */
    List<float[]> embedBatch(List<String> inputs);

    int dim();
}
```

- [ ] **Step 2: OpenAI implementation**

```java
package com.bipros.hds.infrastructure.embedding;

import com.bipros.hds.config.HdsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final HdsProperties props;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${bipros.hds.embedding.openai-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${bipros.hds.embedding.openai-api-key:${OPENAI_API_KEY:}}")
    private String apiKey;

    private WebClient wc;

    private WebClient client() {
        if (wc == null) {
            wc = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        }
        return wc;
    }

    @Override
    public int dim() {
        return props.getEmbedding().getDimensions();
    }

    @Override
    public List<float[]> embedBatch(List<String> inputs) {
        if (inputs.isEmpty()) return List.of();
        ObjectNode req = om.createObjectNode();
        req.put("model", props.getEmbedding().getModel());
        req.put("dimensions", props.getEmbedding().getDimensions());
        var arr = req.putArray("input");
        inputs.forEach(arr::add);

        JsonNode resp = client().post()
            .uri("/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .retryWhen(reactor.util.retry.Retry.backoff(4, Duration.ofSeconds(2)).filter(this::isRetryable))
            .block(Duration.ofMinutes(2));

        if (resp == null || !resp.has("data")) {
            throw new IllegalStateException("Embeddings response missing 'data': " + resp);
        }
        List<float[]> out = new ArrayList<>(inputs.size());
        for (JsonNode item : resp.get("data")) {
            JsonNode emb = item.get("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) vec[i] = emb.get(i).floatValue();
            out.add(vec);
        }
        return out;
    }

    private boolean isRetryable(Throwable t) {
        String msg = t.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("500") || msg.contains("503"));
    }
}
```

> **Open question**: the spec assumes embeddings use the same provider config as chat. If `bipros-ai` already exposes a `LlmProvider` bean with an `embed()` method, the implementer should delegate to that instead and remove the local `apiKey` read. Phase 2 may rewire this — for now, the env-var fallback keeps the module compilable in isolation.

- [ ] **Step 3: Unit test (mocked)**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/embedding/OpenAiEmbeddingClientTest.java
package com.bipros.hds.infrastructure.embedding;

import com.bipros.hds.config.HdsProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiEmbeddingClientTest {

    @Test
    void parsesBatchResponse() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
              {"data":[{"embedding":[0.1,0.2,0.3]},{"embedding":[0.4,0.5,0.6]}]}
              """));

        HdsProperties props = new HdsProperties();
        props.getEmbedding().setModel("text-embedding-3-large");
        props.getEmbedding().setDimensions(3);

        OpenAiEmbeddingClient c = new OpenAiEmbeddingClient(props);
        ReflectionTestUtils.setField(c, "baseUrl", server.url("/").toString().replaceAll("/$", ""));
        ReflectionTestUtils.setField(c, "apiKey", "test-key");

        List<float[]> result = c.embedBatch(List.of("a", "b"));
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f, 0.3f);

        server.shutdown();
    }
}
```

- [ ] **Step 4: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=OpenAiEmbeddingClientTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/embedding \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/embedding
git commit -m "feat(hds): embedding client interface + OpenAI implementation"
```

### Task B.4 — Reranker stub + interface

**Files:**
- Create: `.../infrastructure/reranker/Reranker.java`
- Create: `.../infrastructure/reranker/NoopReranker.java`
- Create: `.../infrastructure/reranker/BgeRerankerClient.java`

- [ ] **Step 1: Interface**

```java
package com.bipros.hds.infrastructure.reranker;

import java.util.List;

public interface Reranker {
    /** Returns indices into `candidates` in best-first order. May return fewer than topK. */
    List<Integer> rerank(String query, List<String> candidates, int topK);
}
```

- [ ] **Step 2: Noop (ships with `reranker.enabled=false`)**

```java
package com.bipros.hds.infrastructure.reranker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

@Component
@ConditionalOnProperty(name = "bipros.hds.reranker.enabled", havingValue = "false", matchIfMissing = true)
public class NoopReranker implements Reranker {
    @Override
    public List<Integer> rerank(String query, List<String> candidates, int topK) {
        return IntStream.range(0, Math.min(topK, candidates.size())).boxed().toList();
    }
}
```

- [ ] **Step 3: BGE HTTP client (used when `reranker.enabled=true`)**

```java
package com.bipros.hds.infrastructure.reranker;

import com.bipros.hds.config.HdsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Calls a BGE-reranker-v2-m3 HTTP service. The service contract:
 * POST /rerank  {"query":"...","documents":["...","..."],"top_k":10}
 *  -> {"results":[{"index":3,"score":0.97}, ...]}  (already top_k, sorted)
 */
@Component
@ConditionalOnProperty(name = "bipros.hds.reranker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BgeRerankerClient implements Reranker {

    private final HdsProperties props;
    private final ObjectMapper om = new ObjectMapper();
    private WebClient wc;

    private WebClient client() {
        if (wc == null) {
            wc = WebClient.builder()
                .baseUrl(props.getReranker().getUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        }
        return wc;
    }

    @Override
    public List<Integer> rerank(String query, List<String> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        ObjectNode req = om.createObjectNode();
        req.put("query", query);
        req.put("top_k", topK);
        var arr = req.putArray("documents");
        candidates.forEach(arr::add);

        JsonNode resp = client().post()
            .uri("/rerank")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(15));

        List<Integer> out = new ArrayList<>();
        if (resp != null && resp.has("results")) {
            resp.get("results").forEach(n -> out.add(n.get("index").asInt()));
        }
        return out;
    }
}
```

- [ ] **Step 4: Unit test for Noop**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/reranker/NoopRerankerTest.java
package com.bipros.hds.infrastructure.reranker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRerankerTest {
    @Test
    void preservesOrderAndCapsAtTopK() {
        var r = new NoopReranker();
        var out = r.rerank("q", List.of("a","b","c","d"), 2);
        assertThat(out).containsExactly(0,1);
    }

    @Test
    void handlesEmpty() {
        var r = new NoopReranker();
        assertThat(r.rerank("q", List.of(), 5)).isEmpty();
    }
}
```

- [ ] **Step 5: Commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=NoopRerankerTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/reranker \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/reranker
git commit -m "feat(hds): reranker interface + noop + BGE HTTP client"
```

---

## Track C — Hybrid search SQL + RRF utility

**Owns**: `backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/retrieval/**`, related test files. **Does NOT touch** `domain/**` or `infrastructure/{docling,storage,embedding,reranker}/**`.

### Task C.1 — `ReciprocalRankFusion` utility

**Files:**
- Create: `.../infrastructure/retrieval/ReciprocalRankFusion.java`

- [ ] **Step 1: Implementation**

```java
package com.bipros.hds.infrastructure.retrieval;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) — combines multiple ranked lists into one.
 * Standard formula: score(d) = sum over ranklists of 1 / (k + rank(d))
 * where k smooths the impact of high ranks. k=60 is the canonical default.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {}

    /**
     * @param ranklists each is an ordered list of doc IDs (rank 0 = highest)
     * @param k smoothing constant (60 by convention)
     * @param limit max items in output
     * @return fused doc IDs in best-first order
     */
    public static <T> List<T> fuse(List<List<T>> ranklists, int k, int limit) {
        Map<T, Double> scores = new HashMap<>();
        for (List<T> list : ranklists) {
            for (int i = 0; i < list.size(); i++) {
                T doc = list.get(i);
                scores.merge(doc, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }
}
```

- [ ] **Step 2: Unit test**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/retrieval/ReciprocalRankFusionTest.java
package com.bipros.hds.infrastructure.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    @Test
    void prefersDocsRankedInMultipleLists() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        var fused = ReciprocalRankFusion.fuse(List.of(
            List.of(a, b, c),
            List.of(b, a, d)
        ), 60, 4);
        // a is rank 0 in list1 and rank 1 in list2 → score = 1/61 + 1/62
        // b is rank 1 in list1 and rank 0 in list2 → score = 1/62 + 1/61  (same as a, ties)
        // c only in list1 at rank 2 → 1/63
        // d only in list2 at rank 2 → 1/63
        assertThat(fused).startsWith(b, a).hasSize(4);  // ordering between a/b indeterminate
    }

    @Test
    void emptyInputsReturnsEmpty() {
        assertThat(ReciprocalRankFusion.fuse(List.<List<String>>of(), 60, 10)).isEmpty();
    }

    @Test
    void singleListEqualsItself() {
        var out = ReciprocalRankFusion.fuse(List.of(List.of("a","b","c")), 60, 3);
        assertThat(out).containsExactly("a","b","c");
    }
}
```

- [ ] **Step 3: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=ReciprocalRankFusionTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/retrieval/ReciprocalRankFusion.java \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/retrieval/ReciprocalRankFusionTest.java
git commit -m "feat(hds): reciprocal rank fusion utility + tests"
```

### Task C.2 — `HybridSearchRepository` (raw JDBC)

**Files:**
- Create: `.../infrastructure/retrieval/HybridSearchRepository.java`
- Create: `.../infrastructure/retrieval/ChunkInsertWriter.java` (used by Phase 2 ingestion)

- [ ] **Step 1: Search repo (vector + BM25 reads, batch chunk insert writes)**

```java
package com.bipros.hds.infrastructure.retrieval;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@Slf4j
@RequiredArgsConstructor
public class HybridSearchRepository {

    private final JdbcTemplate jdbc;

    /** Top-K chunks by cosine similarity within selected versions, filtered by floor. */
    public List<UUID> searchByEmbedding(float[] query, List<UUID> selectedVersionIds,
                                        double similarityFloor, int topK) {
        if (selectedVersionIds.isEmpty()) return List.of();
        PGvector q = new PGvector(query);

        return jdbc.query(con -> {
            Array versions = con.createArrayOf("uuid", selectedVersionIds.toArray());
            var ps = con.prepareStatement(
                "SELECT id FROM hds.hds_chunk " +
                "WHERE hds_version_id = ANY(?) " +
                "  AND 1 - (embedding <=> ?) >= ? " +
                "ORDER BY embedding <=> ? " +
                "LIMIT ?");
            ps.setArray(1, versions);
            ps.setObject(2, q);
            ps.setDouble(3, similarityFloor);
            ps.setObject(4, q);
            ps.setInt(5, topK);
            return ps;
        }, (rs, n) -> (UUID) rs.getObject("id"));
    }

    /** Top-K chunks by BM25-ish keyword score within selected versions. */
    public List<UUID> searchByKeyword(String query, List<UUID> selectedVersionIds, int topK) {
        if (selectedVersionIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            Array versions = con.createArrayOf("uuid", selectedVersionIds.toArray());
            var ps = con.prepareStatement(
                "SELECT id FROM hds.hds_chunk " +
                "WHERE hds_version_id = ANY(?) " +
                "  AND tsv @@ plainto_tsquery('english', ?) " +
                "ORDER BY ts_rank(tsv, plainto_tsquery('english', ?)) DESC " +
                "LIMIT ?");
            ps.setArray(1, versions);
            ps.setString(2, query);
            ps.setString(3, query);
            ps.setInt(4, topK);
            return ps;
        }, (rs, n) -> (UUID) rs.getObject("id"));
    }

    /** Fetch chunks by IDs preserving the given order. */
    public List<ChunkRow> fetchChunks(List<UUID> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        // Use ANY then re-order in Java.
        Object[] ids = chunkIds.toArray();
        List<ChunkRow> rows = jdbc.query(con -> {
            Array a = con.createArrayOf("uuid", ids);
            var ps = con.prepareStatement(
                "SELECT id, hds_version_id, chunk_index, page_start, page_end, section_path, section_number, " +
                "       chunk_type, content, content_tokens " +
                "FROM hds.hds_chunk WHERE id = ANY(?)");
            ps.setArray(1, a);
            return ps;
        }, (rs, n) -> new ChunkRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("hds_version_id"),
            rs.getInt("chunk_index"),
            rs.getInt("page_start"),
            rs.getInt("page_end"),
            rs.getString("section_path"),
            rs.getString("section_number"),
            HdsChunkType.valueOf(rs.getString("chunk_type")),
            rs.getString("content"),
            (Integer) rs.getObject("content_tokens")));
        // Re-order to match input
        var byId = new java.util.HashMap<UUID, ChunkRow>(rows.size());
        rows.forEach(r -> byId.put(r.id(), r));
        var out = new ArrayList<ChunkRow>(chunkIds.size());
        for (UUID id : chunkIds) {
            var c = byId.get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Bulk insert chunks with embeddings using a JDBC batch.
     * `embeddings.size()` must equal `chunks.size()`.
     */
    public int[] insertChunks(List<ChunkInsert> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks/embeddings size mismatch");
        }
        return jdbc.batchUpdate(
            "INSERT INTO hds.hds_chunk " +
            "(id, hds_version_id, chunk_index, page_start, page_end, section_path, section_number, " +
            " chunk_type, content, content_tokens, embedding, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
            new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var c = chunks.get(i);
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, c.hdsVersionId());
                    ps.setInt(3, c.chunkIndex());
                    ps.setInt(4, c.pageStart());
                    ps.setInt(5, c.pageEnd());
                    ps.setString(6, c.sectionPath());
                    ps.setString(7, c.sectionNumber());
                    ps.setString(8, c.chunkType().name());
                    ps.setString(9, c.content());
                    if (c.contentTokens() == null) ps.setNull(10, java.sql.Types.INTEGER);
                    else ps.setInt(10, c.contentTokens());
                    ps.setObject(11, new PGvector(embeddings.get(i)));
                }
                @Override public int getBatchSize() { return chunks.size(); }
            });
    }

    public record ChunkRow(UUID id, UUID hdsVersionId, int chunkIndex, int pageStart, int pageEnd,
                           String sectionPath, String sectionNumber, HdsChunkType chunkType,
                           String content, Integer contentTokens) {}

    public record ChunkInsert(UUID hdsVersionId, int chunkIndex, int pageStart, int pageEnd,
                              String sectionPath, String sectionNumber, HdsChunkType chunkType,
                              String content, Integer contentTokens) {}
}
```

- [ ] **Step 2: Liquibase changelog for HNSW index**

JPA's `ddl-auto: update` won't create HNSW indexes (they're not standard SQL). Add a small Liquibase changelog (idempotent SQL) so dev and prod both apply:

Create `backend/bipros-api/src/main/resources/db/changelog/changesets/2026-05-21-hds-pgvector-indexes.sql`:
```sql
--liquibase formatted sql
--changeset bipros:hds-pgvector-hnsw-index
--validCheckSum: ANY
CREATE INDEX IF NOT EXISTS idx_hds_chunk_embedding_hnsw
  ON hds.hds_chunk USING hnsw (embedding vector_cosine_ops);
--rollback DROP INDEX IF EXISTS hds.idx_hds_chunk_embedding_hnsw;

--changeset bipros:hds-tsv-gin-index
CREATE INDEX IF NOT EXISTS idx_hds_chunk_tsv_gin
  ON hds.hds_chunk USING gin (tsv);
--rollback DROP INDEX IF EXISTS hds.idx_hds_chunk_tsv_gin;
```

Register it in the master changelog `backend/bipros-api/src/main/resources/db/changelog/db.changelog-master.yaml`:
```yaml
databaseChangeLog:
  - include:
      file: classpath:db/changelog/changesets/2026-05-21-hds-pgvector-indexes.sql
```

> If dev uses `ddl-auto: update` only (no Liquibase), Phase 0 must also apply this manually via psql. Add to the Phase 0 verify gate: `psql -h localhost -U bipros -d bipros -f backend/bipros-api/src/main/resources/db/changelog/changesets/2026-05-21-hds-pgvector-indexes.sql` after the schema exists.

- [ ] **Step 3: Integration test using testcontainers + pgvector image**

> **Cross-track note**: this test references `src/test/resources/init-hds-test-schema.sql`, which Track A also creates in its Task A.7. If Track C completes before Track A, also create it here (idempotent content shown in Track A.7 Step 2 — `CREATE EXTENSION IF NOT EXISTS vector; CREATE SCHEMA IF NOT EXISTS hds;`).

Create `backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/retrieval/HybridSearchRepositoryIT.java`:
```java
package com.bipros.hds.infrastructure.retrieval;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.pgvector.PGvector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HybridSearchRepository.class)
@Testcontainers
class HybridSearchRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
        .withDatabaseName("test").withUsername("test").withPassword("test")
        .withInitScript("init-hds-test-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired HybridSearchRepository repo;

    @BeforeAll
    static void schema() {
        // The init script creates schema + extension. We bootstrap the table here.
    }

    @Test
    void roundTripsInsertAndKeywordSearch() {
        jdbc.execute("""
          CREATE TABLE IF NOT EXISTS hds.hds_chunk (
            id uuid primary key,
            hds_version_id uuid not null,
            chunk_index int not null,
            page_start int not null,
            page_end int not null,
            section_path text not null,
            section_number varchar(32),
            chunk_type varchar(16) not null,
            content text not null,
            content_tokens int,
            embedding vector(3) not null,
            tsv tsvector generated always as (to_tsvector('english', content)) stored,
            created_at timestamptz default now(),
            updated_at timestamptz default now()
          );
          CREATE INDEX IF NOT EXISTS idx_test_hds_chunk_tsv ON hds.hds_chunk USING gin(tsv);
          """);

        UUID v = UUID.randomUUID();
        repo.insertChunks(
            List.of(
                new HybridSearchRepository.ChunkInsert(v, 0, 1, 1, "S>1", "1",
                    HdsChunkType.TEXT, "shoulder width specification", 4),
                new HybridSearchRepository.ChunkInsert(v, 1, 2, 2, "S>2", "2",
                    HdsChunkType.TEXT, "lane width minimum 3.0m", 4)
            ),
            List.of(new float[]{0.1f, 0.2f, 0.3f}, new float[]{0.4f, 0.5f, 0.6f}));

        List<UUID> hits = repo.searchByKeyword("shoulder", List.of(v), 10);
        assertThat(hits).hasSize(1);

        var rows = repo.fetchChunks(hits);
        assertThat(rows).singleElement()
            .satisfies(r -> assertThat(r.content()).contains("shoulder"));
    }
}
```

- [ ] **Step 4: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=HybridSearchRepositoryIT -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/infrastructure/retrieval \
        backend/bipros-hds/src/test/java/com/bipros/hds/infrastructure/retrieval \
        backend/bipros-api/src/main/resources/db/changelog/changesets/2026-05-21-hds-pgvector-indexes.sql \
        backend/bipros-api/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(hds): hybrid search repo (vector + bm25) + pgvector HNSW migration"
```

---

## Phase 1 verify gate

After all three tracks complete:
```bash
(cd backend && mvn test -pl bipros-hds -q)
```
Expected: BUILD SUCCESS with the following tests:
- `HdsEntitySmokeTest`
- `DoclingClientTest`
- `OpenAiEmbeddingClientTest`
- `NoopRerankerTest`
- `ReciprocalRankFusionTest`
- `HybridSearchRepositoryIT`

`MinioHdsStorageServiceIT` is gated behind `HDS_RUN_MINIO_IT=true` and not part of the default gate.
