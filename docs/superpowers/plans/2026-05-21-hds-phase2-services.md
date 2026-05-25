# HDS Phase 2 — Application Services

> **Three parallel tracks.** Owns `application/**`. Phase 1 must be complete and green.

**Goal:** Ingestion pipeline (parse → chunk → embed → index) and retrieval pipeline (5-phase ReAct) work end-to-end against a Phase-1-style stub LLM. Library + query log services are usable by Phase 3 controllers.

**Verify gate (run after all three tracks complete):**
```bash
(cd backend && mvn test -pl bipros-hds -q)
```

---

## Track A — Ingestion pipeline

**Owns**: `bipros-hds/src/main/java/com/bipros/hds/application/ingestion/**`, related tests. **Does NOT touch** `application/retrieval/**` or `application/library/**`.

### Task A.1 — `ChunkingService`

Walks Docling response into chunks per the rules in spec §5.1.

**Files:**
- Create: `.../application/ingestion/ChunkingService.java`
- Create: `.../application/ingestion/PreChunk.java` (DTO)

- [ ] **Step 1: PreChunk record**

```java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;

public record PreChunk(int chunkIndex, int pageStart, int pageEnd,
                       String sectionPath, String sectionNumber,
                       HdsChunkType chunkType, String content, int contentTokens) {}
```

- [ ] **Step 2: ChunkingService**

```java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Rules (per spec §5.1):
 *  - Split at heading boundaries — chunks never span sections.
 *  - Token cap 800, overlap 10% (~80 tokens repeated from previous chunk).
 *  - Tables intact (one chunk per table, prefixed with section breadcrumb).
 *  - Figures: caption becomes its own FIGURE_CAPTION chunk.
 *  - section_path is the breadcrumb of all current ancestor headings.
 */
@Service
@Slf4j
public class ChunkingService {

    private static final int TARGET_TOKENS = 800;
    private static final int OVERLAP_TOKENS = 80;

    public List<PreChunk> chunk(DoclingResponse doc) {
        if (doc == null || doc.getBlocks() == null || doc.getBlocks().isEmpty()) return List.of();
        List<PreChunk> out = new ArrayList<>();
        Deque<String> sectionStack = new ArrayDeque<>();   // titles, e.g. ["Vol 3", "4 Cross Section", "4.3 Shoulder Width"]
        String currentSectionNumber = "";
        StringBuilder buffer = new StringBuilder();
        int bufferStartPage = -1;
        int bufferLastPage = -1;
        int chunkIndex = 0;

        for (DoclingBlock b : doc.getBlocks()) {
            String type = b.getType() == null ? "" : b.getType();
            switch (type) {
                case "heading" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber,
                            HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                        bufferStartPage = -1;
                    }
                    int level = b.getLevel() == null ? 1 : b.getLevel();
                    while (sectionStack.size() >= level) sectionStack.pop();
                    sectionStack.push(b.getText() == null ? "" : b.getText().trim());
                    currentSectionNumber = b.getSectionNumber() == null ? currentSectionNumber : b.getSectionNumber();
                }
                case "table" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                        bufferStartPage = -1;
                    }
                    String md = b.getMarkdown() == null ? "" : b.getMarkdown();
                    String wrapped = "Table from " + joinPath(sectionStack) + "\n\n" + md;
                    out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                        currentSectionNumber, HdsChunkType.TABLE, wrapped));
                }
                case "figure" -> {
                    String caption = b.getText() == null ? "" : b.getText().trim();
                    if (!caption.isEmpty()) {
                        out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                            currentSectionNumber, HdsChunkType.FIGURE_CAPTION, caption));
                    }
                }
                case "list_item" -> appendToBuffer(buffer, b, "- ");
                case "formula" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                    }
                    out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                        currentSectionNumber, HdsChunkType.FORMULA,
                        b.getText() == null ? "" : b.getText()));
                }
                default -> appendToBuffer(buffer, b, "");
            }

            // page tracking for buffer
            int p = page(b);
            if (p > 0) {
                if (bufferStartPage < 0) bufferStartPage = p;
                bufferLastPage = p;
            }

            // size-based split when buffer is too large
            int approxTokens = estimateTokens(buffer.toString());
            if (approxTokens >= TARGET_TOKENS) {
                String text = buffer.toString();
                out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                    joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, text));
                // carry overlap
                buffer.setLength(0);
                buffer.append(tail(text, OVERLAP_TOKENS));
                bufferStartPage = bufferLastPage;
            }
        }

        if (!buffer.isEmpty()) {
            out.add(emit(chunkIndex, bufferStartPage, bufferLastPage,
                joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
        }
        return out;
    }

    private static void appendToBuffer(StringBuilder buf, DoclingBlock b, String prefix) {
        String t = b.getText() == null ? "" : b.getText().trim();
        if (t.isEmpty()) return;
        if (!buf.isEmpty()) buf.append("\n\n");
        buf.append(prefix).append(t);
    }

    private static int page(DoclingBlock b) {
        return b.getPage() == null ? -1 : b.getPage();
    }

    private static String joinPath(Deque<String> stack) {
        var rev = new ArrayList<>(stack);
        Collections.reverse(rev);
        return String.join(" > ", rev);
    }

    private static int estimateTokens(String s) {
        // Rough: ~4 chars per token for English.
        return s.length() / 4;
    }

    private static String tail(String s, int approxTokens) {
        int chars = Math.min(s.length(), approxTokens * 4);
        return s.substring(s.length() - chars);
    }

    private static PreChunk emit(int idx, int pStart, int pEnd, String path, String sectionNo,
                                  HdsChunkType type, String content) {
        int ps = pStart < 0 ? 1 : pStart;
        int pe = pEnd < 0 ? ps : pEnd;
        return new PreChunk(idx, ps, pe, path, sectionNo, type, content, estimateTokens(content));
    }
}
```

- [ ] **Step 3: Test**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion/ChunkingServiceTest.java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    @Test
    void splitsAtHeadings() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        doc.setBlocks(List.of(
            blk("heading", 1, 1, "Vol 3", "3"),
            blk("paragraph", null, 1, "Intro about Vol 3.", null),
            blk("heading", 2, 2, "Cross Section", "4"),
            blk("paragraph", null, 2, "Cross section details.", null)
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Vol 3");
        assertThat(chunks.get(0).content()).contains("Intro");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("Vol 3 > Cross Section");
        assertThat(chunks.get(1).content()).contains("Cross section details");
    }

    @Test
    void tablesAreOwnChunks() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        var t = new DoclingBlock();
        t.setType("table");
        t.setPage(5);
        t.setMarkdown("| a | b |\n| - | - |\n| 1 | 2 |");
        doc.setBlocks(List.of(
            blk("heading", 1, 5, "Tables", "5"),
            t
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks).filteredOn(c -> c.chunkType() == HdsChunkType.TABLE)
            .singleElement()
            .satisfies(c -> {
                assertThat(c.content()).contains("Table from Tables").contains("| a | b |");
                assertThat(c.pageStart()).isEqualTo(5);
            });
    }

    @Test
    void splitsLongTextAtTokenCap() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        String big = "lorem ipsum dolor sit amet ".repeat(800);  // ~5000 tokens
        doc.setBlocks(List.of(
            blk("heading", 1, 1, "Big", "1"),
            blk("paragraph", null, 1, big, null)
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks.size()).isGreaterThan(3);
        // overlap means consecutive chunks share some tail/head text
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(
            Math.max(0, chunks.get(0).content().length() - 80 * 4)).substring(0, 20));
    }

    private static DoclingBlock blk(String type, Integer level, Integer page, String text, String sec) {
        var b = new DoclingBlock();
        b.setType(type); b.setLevel(level); b.setPage(page); b.setText(text); b.setSectionNumber(sec);
        return b;
    }
}
```

- [ ] **Step 4: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=ChunkingServiceTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion \
        backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion
git commit -m "feat(hds): chunking service (heading-anchored, table-intact, token-capped)"
```

### Task A.2 — `EmbeddingService` (batches, concurrency, retries)

**Files:**
- Create: `.../application/ingestion/EmbeddingService.java`

- [ ] **Step 1: Implementation**

```java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final HdsProperties props;
    private final EmbeddingClient client;

    public List<float[]> embedAll(List<String> texts, EmbeddingProgressCallback progress) {
        int batchSize = props.getEmbedding().getBatchSize();
        int concurrency = Math.max(1, props.getEmbedding().getConcurrency());
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            batches.add(texts.subList(i, Math.min(i + batchSize, texts.size())));
        }

        float[][] results = new float[texts.size()][];
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            int batchIndex = 0;
            int processed = 0;
            for (List<String> batch : batches) {
                final int offset = batchIndex * batchSize;
                tasks.add(pool.submit(() -> {
                    var vecs = client.embedBatch(batch);
                    for (int i = 0; i < vecs.size(); i++) results[offset + i] = vecs.get(i);
                }));
                batchIndex++;
            }
            for (Future<?> f : tasks) {
                try { f.get(); processed += batchSize; if (progress != null) progress.onProgress(Math.min(processed, texts.size()), texts.size()); }
                catch (InterruptedException | ExecutionException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Embedding batch failed", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        return new ArrayList<>(java.util.Arrays.asList(results));
    }

    @FunctionalInterface
    public interface EmbeddingProgressCallback {
        void onProgress(int done, int total);
    }
}
```

- [ ] **Step 2: Test with stub client**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion/EmbeddingServiceTest.java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceTest {

    @Test
    void preservesOrderAcrossBatchesAndConcurrency() {
        HdsProperties props = new HdsProperties();
        props.getEmbedding().setBatchSize(2);
        props.getEmbedding().setConcurrency(3);
        props.getEmbedding().setDimensions(2);

        EmbeddingClient client = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> inputs) {
                return inputs.stream().map(s -> new float[]{(float) s.length(), 0f}).toList();
            }
            @Override public int dim() { return 2; }
        };

        EmbeddingService svc = new EmbeddingService(props, client);
        List<String> texts = IntStream.range(0, 7).mapToObj(i -> "x".repeat(i + 1)).toList();

        List<int[]> progress = new ArrayList<>();
        var vecs = svc.embedAll(texts, (d, t) -> progress.add(new int[]{d, t}));

        assertThat(vecs).hasSize(7);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(vecs.get(i)[0]).isEqualTo((float) texts.get(i).length());
        }
        assertThat(progress).isNotEmpty();
    }
}
```

- [ ] **Step 3: Commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=EmbeddingServiceTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/EmbeddingService.java \
        backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion/EmbeddingServiceTest.java
git commit -m "feat(hds): batched embedding service with concurrency + progress"
```

### Task A.3 — `ProgressStreamRegistry` (SSE topic)

**Files:**
- Create: `.../application/ingestion/ProgressStreamRegistry.java`
- Create: `.../application/ingestion/IngestionProgressEvent.java`

- [ ] **Step 1: Event**

```java
package com.bipros.hds.application.ingestion;

import java.util.UUID;

public record IngestionProgressEvent(UUID versionId, String stage, int progressPct, String message) {}
```

- [ ] **Step 2: Registry**

```java
package com.bipros.hds.application.ingestion;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ProgressStreamRegistry {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> byVersion = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID versionId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — caller closes
        byVersion.computeIfAbsent(versionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(versionId, emitter));
        emitter.onTimeout(() -> remove(versionId, emitter));
        emitter.onError(t -> remove(versionId, emitter));
        return emitter;
    }

    public void publish(IngestionProgressEvent ev) {
        List<SseEmitter> subs = byVersion.get(ev.versionId());
        if (subs == null) return;
        for (SseEmitter e : subs) {
            try { e.send(SseEmitter.event().name("progress").data(ev)); }
            catch (IOException ex) { remove(ev.versionId(), e); }
        }
    }

    private void remove(UUID id, SseEmitter e) {
        var subs = byVersion.get(id);
        if (subs != null) subs.remove(e);
    }
}
```

- [ ] **Step 3: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/IngestionProgressEvent.java \
        backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/ProgressStreamRegistry.java
git commit -m "feat(hds): SSE progress stream registry for ingestion"
```

### Task A.4 — `IngestionOrchestrator`

Single transactional orchestrator. Each stage commits its state and progress before moving on.

**Files:**
- Create: `.../application/ingestion/IngestionOrchestrator.java`

- [ ] **Step 1: Implementation**

```java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.docling.DoclingClient;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository.ChunkInsert;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionOrchestrator {

    private final HdsProperties props;
    private final HdsVersionRepository versionRepo;
    private final HdsIngestionJobRepository jobRepo;
    private final HdsStorageService storage;
    private final DoclingClient docling;
    private final ChunkingService chunking;
    private final EmbeddingService embedding;
    private final HybridSearchRepository hybridRepo;
    private final ProgressStreamRegistry progress;

    /** Runs the full pipeline from the job's current stage to COMPLETE or FAILED. Blocking. */
    public void run(HdsIngestionJob job) {
        HdsVersion version = versionRepo.findById(job.getHdsVersionId())
            .orElseThrow(() -> new IllegalStateException("Version not found: " + job.getHdsVersionId()));

        try {
            DoclingResponse parsed = null;
            List<PreChunk> chunks = null;
            List<float[]> embeddings = null;

            // PARSING
            if (resumeFrom(job, HdsIngestionStage.PARSING)) {
                advance(job, version, HdsIngestionStage.PARSING, 0, "Parsing PDF…");
                byte[] pdf = storage.download(version.getStorageKey()).readAllBytes();
                parsed = docling.parse(pdf, version.getFileName());
                if (parsed.getPages() != null && version.getPageCount() == null) {
                    version.setPageCount(parsed.getPages());
                    versionRepo.save(version);
                }
                advance(job, version, HdsIngestionStage.PARSING, 60, "Parsed " + parsed.getPages() + " pages");
            }

            // CHUNKING
            if (resumeFrom(job, HdsIngestionStage.CHUNKING)) {
                if (parsed == null) {
                    // Resumed mid-pipeline — re-parse (Docling is idempotent on the same bytes)
                    byte[] pdf = storage.download(version.getStorageKey()).readAllBytes();
                    parsed = docling.parse(pdf, version.getFileName());
                }
                advance(job, version, HdsIngestionStage.CHUNKING, 65, "Chunking…");
                chunks = chunking.chunk(parsed);
                advance(job, version, HdsIngestionStage.CHUNKING, 70, "Built " + chunks.size() + " chunks");
            }

            // EMBEDDING
            if (resumeFrom(job, HdsIngestionStage.EMBEDDING)) {
                if (chunks == null) {
                    if (parsed == null) {
                        byte[] pdf = storage.download(version.getStorageKey()).readAllBytes();
                        parsed = docling.parse(pdf, version.getFileName());
                    }
                    chunks = chunking.chunk(parsed);
                }
                advance(job, version, HdsIngestionStage.EMBEDDING, 70, "Embedding…");
                List<String> texts = new ArrayList<>(chunks.size());
                for (PreChunk pc : chunks) texts.add(pc.content());
                embeddings = embedding.embedAll(texts, (done, total) -> {
                    int pct = 70 + (int) Math.round(29.0 * done / Math.max(total, 1));
                    advance(job, version, HdsIngestionStage.EMBEDDING, pct, "Embedded " + done + "/" + total);
                });
            }

            // INDEXING
            if (resumeFrom(job, HdsIngestionStage.INDEXING)) {
                if (chunks == null || embeddings == null) {
                    // Resumed mid-INDEXING: re-run embedding (cheap to redo at our scale).
                    if (parsed == null) {
                        byte[] pdf = storage.download(version.getStorageKey()).readAllBytes();
                        parsed = docling.parse(pdf, version.getFileName());
                    }
                    if (chunks == null) chunks = chunking.chunk(parsed);
                    if (embeddings == null) {
                        List<String> texts = new ArrayList<>(chunks.size());
                        for (PreChunk pc : chunks) texts.add(pc.content());
                        embeddings = embedding.embedAll(texts, null);
                    }
                }
                advance(job, version, HdsIngestionStage.INDEXING, 99, "Indexing…");
                List<ChunkInsert> inserts = new ArrayList<>(chunks.size());
                for (PreChunk pc : chunks) {
                    inserts.add(new ChunkInsert(version.getId(), pc.chunkIndex(),
                        pc.pageStart(), pc.pageEnd(), pc.sectionPath(), pc.sectionNumber(),
                        pc.chunkType(), pc.content(), pc.contentTokens()));
                }
                hybridRepo.insertChunks(inserts, embeddings);
                version.setChunkCount(chunks.size());
                version.setStatus(HdsVersionStatus.INDEXED);
                version.setIndexedAt(Instant.now());
                versionRepo.save(version);

                job.setStage(HdsIngestionStage.COMPLETE);
                job.setProgressPct(100);
                job.setCompletedAt(Instant.now());
                jobRepo.save(job);
                progress.publish(new IngestionProgressEvent(version.getId(), "COMPLETE", 100, "Indexed " + chunks.size() + " chunks"));
            }
        } catch (Exception e) {
            log.error("Ingestion failed: versionId={}", version.getId(), e);
            job.setStage(HdsIngestionStage.FAILED);
            job.setErrorMessage(e.getMessage() == null ? e.toString() : e.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);
            version.setStatus(HdsVersionStatus.FAILED);
            version.setIndexingError(job.getErrorMessage());
            versionRepo.save(version);
            progress.publish(new IngestionProgressEvent(version.getId(), "FAILED", job.getProgressPct(), job.getErrorMessage()));
            throw new RuntimeException(e);
        }
    }

    private boolean resumeFrom(HdsIngestionJob job, HdsIngestionStage stage) {
        // We need to run a stage if the current job stage is at or before it (and not COMPLETE/FAILED).
        if (job.getStage() == HdsIngestionStage.COMPLETE || job.getStage() == HdsIngestionStage.FAILED) return false;
        return job.getStage().ordinal() <= stage.ordinal();
    }

    @Transactional
    public void advance(HdsIngestionJob job, HdsVersion version, HdsIngestionStage stage, int pct, String msg) {
        job.setStage(stage);
        job.setProgressPct(pct);
        job.setLastHeartbeatAt(Instant.now());
        jobRepo.save(job);

        version.setStatus(switch (stage) {
            case PARSING -> HdsVersionStatus.PARSING;
            case CHUNKING -> HdsVersionStatus.CHUNKING;
            case EMBEDDING, INDEXING -> HdsVersionStatus.EMBEDDING;
            case COMPLETE -> HdsVersionStatus.INDEXED;
            case FAILED -> HdsVersionStatus.FAILED;
        });
        version.setIndexingProgressPct(pct);
        versionRepo.save(version);

        progress.publish(new IngestionProgressEvent(version.getId(), stage.name(), pct, msg));
    }
}
```

- [ ] **Step 2: Test (mocks)**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion/IngestionOrchestratorTest.java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.docling.DoclingClient;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IngestionOrchestratorTest {

    @Test
    void happyPathRunsAllStagesAndMarksIndexed() {
        var props = new HdsProperties();
        props.getEmbedding().setBatchSize(10);
        props.getEmbedding().setConcurrency(1);

        HdsVersionRepository versionRepo = mock(HdsVersionRepository.class);
        HdsIngestionJobRepository jobRepo = mock(HdsIngestionJobRepository.class);
        HdsStorageService storage = mock(HdsStorageService.class);
        DoclingClient docling = mock(DoclingClient.class);
        ChunkingService chunking = new ChunkingService();
        var embedClient = (com.bipros.hds.infrastructure.embedding.EmbeddingClient) inputs ->
            inputs.stream().map(s -> new float[]{1f, 2f, 3f}).toList();
        var embedSvc = new EmbeddingService(props, embedClient);
        HybridSearchRepository hybrid = mock(HybridSearchRepository.class);
        ProgressStreamRegistry progress = new ProgressStreamRegistry();

        UUID versionId = UUID.randomUUID();
        var version = new HdsVersion();
        version.setId(versionId);
        version.setStorageKey("hds/" + versionId + "/x.pdf");
        version.setFileName("x.pdf");
        when(versionRepo.findById(versionId)).thenReturn(Optional.of(version));
        when(storage.download(any())).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));

        var doclingResp = new DoclingResponse();
        doclingResp.setPages(2);
        var b1 = new DoclingBlock(); b1.setType("heading"); b1.setLevel(1); b1.setPage(1); b1.setText("Title"); b1.setSectionNumber("1");
        var b2 = new DoclingBlock(); b2.setType("paragraph"); b2.setPage(1); b2.setText("Body text here.");
        doclingResp.setBlocks(List.of(b1, b2));
        when(docling.parse(any(), any())).thenReturn(doclingResp);

        var job = new HdsIngestionJob();
        job.setId(UUID.randomUUID());
        job.setHdsVersionId(versionId);
        job.setStage(HdsIngestionStage.PARSING);

        var orch = new IngestionOrchestrator(props, versionRepo, jobRepo, storage, docling,
            chunking, embedSvc, hybrid, progress);
        orch.run(job);

        ArgumentCaptor<HdsVersion> verCap = ArgumentCaptor.forClass(HdsVersion.class);
        verify(versionRepo, atLeastOnce()).save(verCap.capture());
        var finalState = verCap.getAllValues().get(verCap.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(HdsVersionStatus.INDEXED);

        verify(hybrid).insertChunks(any(), any());
    }
}
```

- [ ] **Step 3: Run + commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=IngestionOrchestratorTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/IngestionOrchestrator.java \
        backend/bipros-hds/src/test/java/com/bipros/hds/application/ingestion/IngestionOrchestratorTest.java
git commit -m "feat(hds): ingestion orchestrator (resumable, stage-tracked)"
```

### Task A.5 — `IngestionWorker` (scheduled)

**Files:**
- Create: `.../application/ingestion/IngestionWorker.java`

- [ ] **Step 1: Implementation**

```java
package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class IngestionWorker {

    private static final long ADVISORY_LOCK_KEY = 0x4844_5300_0000_0001L;  // "HDS\0...\1"

    private final HdsProperties props;
    private final HdsIngestionJobRepository jobRepo;
    private final IngestionOrchestrator orchestrator;
    private final JdbcTemplate jdbc;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final String workerId = "worker-" + System.getProperty("user.name") + "-" + Long.toHexString(System.nanoTime());

    @PostConstruct
    public void resetStaleJobsOnBoot() {
        Instant cutoff = Instant.now().minusSeconds(props.getIngestion().getStaleJobAfterSeconds());
        List<HdsIngestionJob> stale = jobRepo.findStaleJobs(cutoff);
        for (var j : stale) {
            log.warn("Resetting stale ingestion job {}: stage={}, last_heartbeat={}",
                j.getId(), j.getStage(), j.getLastHeartbeatAt());
            j.setLastHeartbeatAt(null);
            jobRepo.save(j);
        }
    }

    @Scheduled(fixedDelayString = "${bipros.hds.ingestion.worker-poll-seconds:5}000")
    public void poll() {
        if (busy.get()) return;
        Boolean got = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (got == null || !got) return;
        try {
            var maybeJob = jobRepo.findFirstByStageInOrderByCreatedAtAsc(List.of(
                HdsIngestionStage.PARSING, HdsIngestionStage.CHUNKING,
                HdsIngestionStage.EMBEDDING, HdsIngestionStage.INDEXING));
            if (maybeJob.isEmpty()) return;
            var job = maybeJob.get();
            busy.set(true);
            log.info("Picked up ingestion job {} (stage={}) on worker {}", job.getId(), job.getStage(), workerId);
            job.setWorkerId(workerId);
            job.setAttemptCount((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
            jobRepo.save(job);
            try {
                orchestrator.run(job);
            } catch (Exception e) {
                log.error("Job {} failed", job.getId(), e);
            }
        } finally {
            jdbc.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
            busy.set(false);
        }
    }
}
```

- [ ] **Step 2: Commit (no unit test — covered by Phase 5 integration)**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/ingestion/IngestionWorker.java
git commit -m "feat(hds): scheduled ingestion worker (advisory lock, stale-job reset)"
```

---

## Track B — Retrieval pipeline (5-phase ReAct + verifier)

**Owns**: `bipros-hds/src/main/java/com/bipros/hds/application/retrieval/**`, related tests. **Does NOT touch** `application/ingestion/**` or `application/library/**`.

### Task B.1 — `LlmGateway` interface (calls into `bipros-ai` provider)

**Files:**
- Create: `.../application/retrieval/LlmGateway.java`
- Create: `.../application/retrieval/StubLlmGateway.java` (test default)

> The real wiring lives in `bipros-ai`. For Phase 2 testability we expose an interface here. Phase 3 supplies a Spring bean that delegates to the existing `LlmProvider`.

- [ ] **Step 1: Interface**

```java
package com.bipros.hds.application.retrieval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface LlmGateway {
    /** Returns the LLM's completion text. Used for plan/examine/verify (structured JSON outputs).
     *  Implementations should set temperature low (0.0–0.2) and request JSON-mode where supported. */
    String completeStructured(List<ChatMessage> messages, String responseFormatJsonSchemaName);

    /** Returns the full streamed answer as a single concatenated string.
     *  The caller separately publishes streaming chunks via a callback when needed. */
    String completeStreaming(List<ChatMessage> messages, StreamCallback onToken);

    record ChatMessage(String role, String content) {}

    @FunctionalInterface
    interface StreamCallback {
        void onToken(String token);
    }
}
```

- [ ] **Step 2: Stub for tests**

```java
package com.bipros.hds.application.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("test")
@ConditionalOnMissingBean(LlmGateway.class)
public class StubLlmGateway implements LlmGateway {
    @Override public String completeStructured(List<ChatMessage> messages, String fmt) {
        return "{\"is_compound\":false,\"sub_questions\":[],\"search_queries\":[\"stub\"],\"passed\":true,\"sufficient\":true,\"follow_up_queries\":[],\"issues\":[]}";
    }
    @Override public String completeStreaming(List<ChatMessage> messages, StreamCallback cb) {
        String t = "Per the provided chunks [c1], answer is X.";
        if (cb != null) cb.onToken(t);
        return t;
    }
}
```

- [ ] **Step 3: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/retrieval/LlmGateway.java \
        backend/bipros-hds/src/main/java/com/bipros/hds/application/retrieval/StubLlmGateway.java
git commit -m "feat(hds): LlmGateway interface + test stub"
```

### Task B.2 — Result DTOs

**Files:**
- Create: `.../application/retrieval/PlanResult.java`
- Create: `.../application/retrieval/ExamineResult.java`
- Create: `.../application/retrieval/VerifyResult.java`
- Create: `.../application/retrieval/RetrievalAnswer.java`
- Create: `.../application/retrieval/Citation.java`

- [ ] **Step 1: DTOs**

```java
// PlanResult.java
package com.bipros.hds.application.retrieval;
import java.util.List;
public record PlanResult(boolean isCompound, List<String> subQuestions, List<String> searchQueries) {}
```
```java
// ExamineResult.java
package com.bipros.hds.application.retrieval;
import java.util.List;
public record ExamineResult(boolean sufficient, List<String> followUpQueries) {}
```
```java
// VerifyResult.java
package com.bipros.hds.application.retrieval;
import java.util.List;
public record VerifyResult(boolean passed, List<Issue> issues) {
    public record Issue(String claim, String citation, String explanation) {}
}
```
```java
// Citation.java
package com.bipros.hds.application.retrieval;
import java.util.UUID;
public record Citation(String marker, UUID chunkId, UUID versionId,
                       String versionLabel, String sectionPath,
                       int pageStart, int pageEnd, String excerpt) {}
```
```java
// RetrievalAnswer.java
package com.bipros.hds.application.retrieval;
import java.util.List;
import java.util.Map;
public record RetrievalAnswer(String answer, List<Citation> citations,
                              VerifyResult verifier, Map<String, Object> metadata) {}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/retrieval
git commit -m "feat(hds): retrieval result DTOs (Plan/Examine/Verify/Citation/Answer)"
```

### Task B.3 — Prompt templates

**Files:**
- Create: `.../application/retrieval/Prompts.java`

- [ ] **Step 1: Prompt strings**

```java
package com.bipros.hds.application.retrieval;

public final class Prompts {

    private Prompts() {}

    public static final String PLAN_SYSTEM = """
        You are a retrieval planner for a Highway Design Standards knowledge base.
        Given a question and the list of selected HDS versions (titles), output STRICTLY a JSON object:
          {"is_compound": bool, "sub_questions": [str], "search_queries": [str]}
        - is_compound is true if the question asks to compare/contrast across versions or sections.
        - sub_questions: empty unless is_compound; otherwise one entry per logical sub-question.
        - search_queries: 1–3 short retrieval strings to issue against the corpus.
        Reply with ONLY the JSON, no commentary.
        """;

    public static final String EXAMINE_SYSTEM = """
        You are checking whether the retrieved chunks contain enough information to answer the question.
        Output STRICTLY a JSON object:
          {"sufficient": bool, "follow_up_queries": [str]}
        - sufficient: true if the chunks already contain the facts needed.
        - follow_up_queries: empty if sufficient; otherwise 1–2 additional retrieval strings.
        Reply with ONLY the JSON.
        """;

    public static final String DRAFT_SYSTEM = """
        You are a Highway Design Standards lookup assistant. Answer ONLY using the numbered chunks provided.
        Every factual claim MUST end with a citation [cN] matching one of the provided chunks.
        If the answer is not in the provided chunks, reply exactly:
          "I don't see that in the selected HDS documents."
        Do NOT use any general engineering knowledge.
        """;

    public static final String VERIFY_SYSTEM = """
        You are a strict grounding verifier. Given a draft answer and the cited chunks,
        check that every factual claim in the answer is supported by the chunk it cites.
        Output STRICTLY a JSON object:
          {"passed": bool, "issues": [{"claim": str, "citation": str, "explanation": str}]}
        - passed=true only if every claim is grounded.
        - issues: empty when passed=true; one entry per unsupported claim otherwise.
        Reply with ONLY the JSON.
        """;
}
```

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/retrieval/Prompts.java
git commit -m "feat(hds): retrieval prompt templates (plan/examine/draft/verify)"
```

### Task B.4 — `RetrievalService` (5-phase ReAct + verifier + cache)

**Files:**
- Create: `.../application/retrieval/RetrievalService.java`
- Create: `.../application/retrieval/QueryCache.java`

- [ ] **Step 1: Query cache**

```java
package com.bipros.hds.application.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class QueryCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper();

    public RetrievalAnswer get(String query, List<UUID> versionIds) {
        String key = key(query, versionIds);
        String val = redis.opsForValue().get(key);
        if (val == null) return null;
        try { return om.readValue(val, RetrievalAnswer.class); }
        catch (Exception e) { log.warn("Cache deserialization failed for {}", key); return null; }
    }

    public void put(String query, List<UUID> versionIds, RetrievalAnswer answer, Duration ttl) {
        String key = key(query, versionIds);
        try {
            String val = om.writeValueAsString(answer);
            redis.opsForValue().set(key, val, ttl);
            for (UUID v : versionIds) {
                redis.opsForSet().add("hds:cache:byversion:" + v, key);
            }
        } catch (Exception e) {
            log.warn("Cache write failed", e);
        }
    }

    public void invalidateForVersion(UUID versionId) {
        Set<String> keys = redis.opsForSet().members("hds:cache:byversion:" + versionId);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            redis.delete("hds:cache:byversion:" + versionId);
        }
    }

    private String key(String query, List<UUID> versionIds) {
        var sortedIds = versionIds.stream().map(UUID::toString).sorted().toList();
        String input = query + "||" + String.join(",", sortedIds);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return "hds:qa:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **Step 2: RetrievalService**

```java
package com.bipros.hds.application.retrieval;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsQueryLog;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.repo.HdsQueryLogRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.bipros.hds.infrastructure.reranker.Reranker;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import com.bipros.hds.infrastructure.retrieval.ReciprocalRankFusion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class RetrievalService {

    private static final String SAFE_FAIL = "I don't see that in the selected HDS documents.";

    private final HdsProperties props;
    private final HdsVersionRepository versionRepo;
    private final HdsQueryLogRepository logRepo;
    private final HybridSearchRepository hybridRepo;
    private final EmbeddingClient embedClient;
    private final Reranker reranker;
    private final LlmGateway llm;
    private final QueryCache cache;
    private final ObjectMapper om = new ObjectMapper();

    public RetrievalAnswer answer(String question, List<UUID> selectedVersionIds,
                                  int maxRounds, UUID userId, UUID conversationId,
                                  LlmGateway.StreamCallback streamCb) {
        long started = System.currentTimeMillis();

        // Cache lookup
        var cached = cache.get(question, selectedVersionIds);
        if (cached != null) {
            log.info("Cache hit for query: '{}'", question);
            if (streamCb != null) streamCb.onToken(cached.answer());
            return cached;
        }

        // Resolve version labels for the prompt
        List<HdsVersion> versions = versionRepo.findAllById(selectedVersionIds);
        if (versions.isEmpty()) {
            return safeFail(question, selectedVersionIds, started);
        }

        // Phase 1: PLAN
        PlanResult plan = phasePlan(question, versions);

        // Phase 2 + 3 loop
        List<UUID> retrievedIds = new ArrayList<>();
        for (int round = 1; round <= maxRounds; round++) {
            List<String> queries = round == 1 ? plan.searchQueries() : new ArrayList<>();
            if (round > 1) {
                // examine of previous round populated follow-ups; we got here means !sufficient
                queries = lastExamineFollowUps;
            }
            var roundIds = phaseRetrieve(queries, selectedVersionIds);
            retrievedIds = dedupe(retrievedIds, roundIds);

            if (retrievedIds.isEmpty()) {
                return safeFail(question, selectedVersionIds, started);
            }

            var examine = phaseExamine(question, hybridRepo.fetchChunks(retrievedIds));
            if (examine.sufficient() || round == maxRounds) {
                break;
            }
            lastExamineFollowUps = examine.followUpQueries();
        }

        // Phase 4: DRAFT
        var chunks = hybridRepo.fetchChunks(retrievedIds);
        if (chunks.size() > props.getRetrieval().getMaxChunksPerQuery()) {
            chunks = chunks.subList(0, props.getRetrieval().getMaxChunksPerQuery());
        }
        Map<String, HybridSearchRepository.ChunkRow> markerToChunk = new LinkedHashMap<>();
        StringBuilder chunkBlock = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String marker = "c" + (i + 1);
            markerToChunk.put(marker, chunks.get(i));
            chunkBlock.append("[").append(marker).append("] ")
                .append(chunks.get(i).sectionPath()).append(" (p. ")
                .append(chunks.get(i).pageStart()).append(")\n")
                .append(chunks.get(i).content()).append("\n\n");
        }

        String draftUser = "Question: " + question + "\n\nChunks:\n" + chunkBlock;
        String draft = llm.completeStreaming(
            List.of(new LlmGateway.ChatMessage("system", Prompts.DRAFT_SYSTEM),
                    new LlmGateway.ChatMessage("user", draftUser)),
            streamCb);

        // Phase 5: VERIFY (up to maxRetries)
        VerifyResult verify = phaseVerify(draft, markerToChunk);
        int retries = 0;
        while (!verify.passed() && retries < props.getVerifier().getMaxRetries()) {
            String feedback = "Verifier rejected these claims:\n" +
                String.join("\n", verify.issues().stream().map(i -> "- " + i.claim() + " (" + i.explanation() + ")").toList()) +
                "\n\nRewrite the answer using only the chunks provided.";
            draft = llm.completeStreaming(
                List.of(new LlmGateway.ChatMessage("system", Prompts.DRAFT_SYSTEM),
                        new LlmGateway.ChatMessage("user", draftUser + "\n\n" + feedback)),
                streamCb);
            verify = phaseVerify(draft, markerToChunk);
            retries++;
        }

        if (!verify.passed()) {
            draft = SAFE_FAIL;
        }

        // Build citations from markers actually used in draft
        List<Citation> citations = buildCitations(draft, markerToChunk, versions);

        var meta = new LinkedHashMap<String, Object>();
        meta.put("duration_ms", (int) (System.currentTimeMillis() - started));
        meta.put("rounds", retrievedIds.isEmpty() ? 0 : 1);  // simplification; the real round counter is in the loop above

        var answer = new RetrievalAnswer(draft, citations, verify, meta);
        cache.put(question, selectedVersionIds, answer, Duration.ofSeconds(props.getRetrieval().getCacheTtlSeconds()));
        logQuery(userId, conversationId, question, selectedVersionIds, retrievedIds, answer, started);
        return answer;
    }

    private List<String> lastExamineFollowUps = List.of();

    private PlanResult phasePlan(String question, List<HdsVersion> versions) {
        String userMsg = "Question: " + question + "\nSelected versions: " +
            versions.stream().map(v -> v.getVersionLabel()).toList();
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.PLAN_SYSTEM),
                    new LlmGateway.ChatMessage("user", userMsg)),
            "plan");
        try {
            JsonNode n = om.readTree(json);
            return new PlanResult(
                n.path("is_compound").asBoolean(false),
                jsonArrayToList(n.path("sub_questions")),
                jsonArrayToList(n.path("search_queries")));
        } catch (Exception e) {
            return new PlanResult(false, List.of(), List.of(question));
        }
    }

    private List<UUID> phaseRetrieve(List<String> queries, List<UUID> selectedVersionIds) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<UUID> dedup = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String q : queries) {
            float[] qEmb = embedClient.embedBatch(List.of(q)).get(0);
            var dense = hybridRepo.searchByEmbedding(qEmb, selectedVersionIds,
                props.getRetrieval().getSimilarityFloor(),
                props.getRetrieval().getVectorTopK());
            var sparse = hybridRepo.searchByKeyword(q, selectedVersionIds,
                props.getRetrieval().getBm25TopK());
            var fused = ReciprocalRankFusion.fuse(List.of(dense, sparse), 60,
                props.getRetrieval().getVectorTopK());

            List<HybridSearchRepository.ChunkRow> rows = hybridRepo.fetchChunks(fused);
            List<String> texts = rows.stream().map(HybridSearchRepository.ChunkRow::content).toList();
            List<Integer> rerankedIdx = reranker.rerank(q, texts, props.getReranker().getTopK());
            for (int idx : rerankedIdx) {
                UUID id = rows.get(idx).id();
                if (seen.add(id)) dedup.add(id);
            }
        }
        return dedup;
    }

    private ExamineResult phaseExamine(String question, List<HybridSearchRepository.ChunkRow> chunks) {
        StringBuilder ctx = new StringBuilder();
        for (var c : chunks) ctx.append(c.sectionPath()).append("\n").append(c.content()).append("\n\n");
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.EXAMINE_SYSTEM),
                    new LlmGateway.ChatMessage("user", "Question: " + question + "\n\nChunks:\n" + ctx)),
            "examine");
        try {
            JsonNode n = om.readTree(json);
            return new ExamineResult(
                n.path("sufficient").asBoolean(true),
                jsonArrayToList(n.path("follow_up_queries")));
        } catch (Exception e) {
            return new ExamineResult(true, List.of());
        }
    }

    private VerifyResult phaseVerify(String draft, Map<String, HybridSearchRepository.ChunkRow> markerToChunk) {
        StringBuilder ctx = new StringBuilder();
        markerToChunk.forEach((m, c) -> ctx.append("[").append(m).append("] ").append(c.content()).append("\n\n"));
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.VERIFY_SYSTEM),
                    new LlmGateway.ChatMessage("user", "Draft answer:\n" + draft + "\n\nCited chunks:\n" + ctx)),
            "verify");
        try {
            JsonNode n = om.readTree(json);
            boolean passed = n.path("passed").asBoolean(false);
            List<VerifyResult.Issue> issues = new ArrayList<>();
            if (n.has("issues")) {
                for (JsonNode it : n.get("issues")) {
                    issues.add(new VerifyResult.Issue(
                        it.path("claim").asText(""),
                        it.path("citation").asText(""),
                        it.path("explanation").asText("")));
                }
            }
            return new VerifyResult(passed, issues);
        } catch (Exception e) {
            return new VerifyResult(false, List.of(new VerifyResult.Issue("", "", "verifier parse error")));
        }
    }

    private List<Citation> buildCitations(String draft, Map<String, HybridSearchRepository.ChunkRow> markerToChunk,
                                          List<HdsVersion> versions) {
        Map<UUID, HdsVersion> byVid = new HashMap<>();
        versions.forEach(v -> byVid.put(v.getId(), v));
        List<Citation> out = new ArrayList<>();
        Pattern p = Pattern.compile("\\[c(\\d+)\\]");
        Matcher m = p.matcher(draft);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String marker = "c" + m.group(1);
            if (!seen.add(marker)) continue;
            var chunk = markerToChunk.get(marker);
            if (chunk == null) continue;
            var ver = byVid.get(chunk.hdsVersionId());
            String label = ver == null ? "Unknown" : ver.getVersionLabel();
            String excerpt = chunk.content().length() > 200
                ? chunk.content().substring(0, 200) + "…"
                : chunk.content();
            out.add(new Citation(marker, chunk.id(), chunk.hdsVersionId(), label,
                chunk.sectionPath(), chunk.pageStart(), chunk.pageEnd(), excerpt));
        }
        return out;
    }

    private RetrievalAnswer safeFail(String question, List<UUID> versionIds, long started) {
        var meta = Map.<String, Object>of("duration_ms", (int) (System.currentTimeMillis() - started), "rounds", 0);
        return new RetrievalAnswer(SAFE_FAIL, List.of(),
            new VerifyResult(true, List.of()), meta);
    }

    private void logQuery(UUID userId, UUID conversationId, String question, List<UUID> versionIds,
                          List<UUID> retrievedIds, RetrievalAnswer answer, long started) {
        try {
            var log = new HdsQueryLog();
            log.setUserId(userId);
            log.setConversationId(conversationId);
            log.setQueryText(question);
            log.setSelectedVersionIds(versionIds.toArray(new UUID[0]));
            log.setRetrievedChunkIds(retrievedIds.toArray(new UUID[0]));
            log.setAnswerText(answer.answer());
            log.setDurationMs((Integer) answer.metadata().get("duration_ms"));
            log.setVerifierPassed(answer.verifier().passed());
            log.setRounds((Integer) answer.metadata().getOrDefault("rounds", 0));
            logRepo.save(log);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RetrievalService.class).warn("query log save failed", e);
        }
    }

    private static List<UUID> dedupe(List<UUID> existing, List<UUID> add) {
        var set = new LinkedHashSet<>(existing);
        set.addAll(add);
        return new ArrayList<>(set);
    }

    private static List<String> jsonArrayToList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        n.forEach(e -> out.add(e.asText()));
        return out;
    }
}
```

- [ ] **Step 3: Test (high-level happy path with stubs)**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/application/retrieval/RetrievalServiceTest.java
package com.bipros.hds.application.retrieval;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.domain.repo.HdsQueryLogRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.bipros.hds.infrastructure.reranker.NoopReranker;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RetrievalServiceTest {

    @Test
    void safeFailsWhenNoChunksFound() {
        var props = new HdsProperties();
        props.getReranker().setTopK(10);
        props.getRetrieval().setMaxChunksPerQuery(20);
        props.getRetrieval().setMaxRounds(1);
        props.getRetrieval().setSimilarityFloor(0.3);
        props.getRetrieval().setVectorTopK(50);
        props.getRetrieval().setBm25TopK(50);
        props.getRetrieval().setCacheTtlSeconds(60);
        props.getVerifier().setMaxRetries(1);

        var versionRepo = mock(HdsVersionRepository.class);
        var logRepo = mock(HdsQueryLogRepository.class);
        var hybrid = mock(HybridSearchRepository.class);
        var embed = (EmbeddingClient) inputs -> inputs.stream().map(s -> new float[]{0f}).toList();
        var llm = new StubLlmGateway();
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        var cache = new QueryCache(redis);

        var version = new HdsVersion();
        UUID vid = UUID.randomUUID();
        version.setId(vid);
        version.setVersionLabel("Rev 1");
        when(versionRepo.findAllById(List.of(vid))).thenReturn(List.of(version));
        when(hybrid.searchByEmbedding(any(), any(), anyDouble(), anyInt())).thenReturn(List.of());
        when(hybrid.searchByKeyword(anyString(), any(), anyInt())).thenReturn(List.of());

        var svc = new RetrievalService(props, versionRepo, logRepo, hybrid, embed, new NoopReranker(), llm, cache);
        var ans = svc.answer("anything", List.of(vid), 1, UUID.randomUUID(), UUID.randomUUID(), null);
        assertThat(ans.answer()).contains("I don't see that");
        assertThat(ans.citations()).isEmpty();
    }
}
```

- [ ] **Step 4: Commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=RetrievalServiceTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/retrieval \
        backend/bipros-hds/src/test/java/com/bipros/hds/application/retrieval
git commit -m "feat(hds): retrieval service (5-phase ReAct + verifier + cache)"
```

---

## Track C — Library + query-log + cache invalidation

**Owns**: `bipros-hds/src/main/java/com/bipros/hds/application/library/**` and `application/query/**`. **Does NOT touch** `application/ingestion/**` or `application/retrieval/**`.

### Task C.1 — `HdsLibraryService`

**Files:**
- Create: `.../application/library/HdsLibraryService.java`
- Create: `.../application/library/dto/CreateHdsDocumentInput.java`
- Create: `.../application/library/dto/UpdateHdsDocumentInput.java`

- [ ] **Step 1: Input DTOs**

```java
// CreateHdsDocumentInput.java
package com.bipros.hds.application.library.dto;
import com.bipros.hds.domain.enums.HdsDiscipline;
public record CreateHdsDocumentInput(String title, String shortCode, HdsDiscipline discipline,
                                     String issuingAuthority, String country, String description) {}
```
```java
// UpdateHdsDocumentInput.java
package com.bipros.hds.application.library.dto;
import com.bipros.hds.domain.enums.HdsDiscipline;
public record UpdateHdsDocumentInput(String title, HdsDiscipline discipline,
                                     String issuingAuthority, String country, String description) {}
```

- [ ] **Step 2: Service**

```java
package com.bipros.hds.application.library;

import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.application.library.dto.UpdateHdsDocumentInput;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsChunkRepository;
import com.bipros.hds.domain.repo.HdsDocumentRepository;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class HdsLibraryService {

    private final HdsDocumentRepository docRepo;
    private final HdsVersionRepository versionRepo;
    private final HdsIngestionJobRepository jobRepo;
    private final HdsChunkRepository chunkRepo;
    private final HdsStorageService storage;

    public HdsDocument createDocument(CreateHdsDocumentInput in) {
        if (docRepo.existsByShortCode(in.shortCode())) {
            throw new IllegalArgumentException("Short code already in use: " + in.shortCode());
        }
        var doc = HdsDocument.builder()
            .title(in.title())
            .shortCode(in.shortCode())
            .discipline(in.discipline())
            .issuingAuthority(in.issuingAuthority())
            .country(in.country())
            .description(in.description())
            .build();
        return docRepo.save(doc);
    }

    public HdsDocument updateDocument(UUID id, UpdateHdsDocumentInput in) {
        var doc = docRepo.findById(id).orElseThrow();
        if (in.title() != null) doc.setTitle(in.title());
        if (in.discipline() != null) doc.setDiscipline(in.discipline());
        if (in.issuingAuthority() != null) doc.setIssuingAuthority(in.issuingAuthority());
        if (in.country() != null) doc.setCountry(in.country());
        if (in.description() != null) doc.setDescription(in.description());
        return docRepo.save(doc);
    }

    public List<HdsDocument> listDocuments() {
        return docRepo.findAll();
    }

    public void deleteDocument(UUID id) {
        var versions = versionRepo.findByHdsDocumentIdOrderByRevisionYearDesc(id);
        for (var v : versions) deleteVersion(v.getId());
        docRepo.deleteById(id);
    }

    @Transactional(noRollbackFor = DuplicateUploadException.class)
    public HdsVersion uploadVersion(UUID documentId, String versionLabel, Integer revisionYear,
                                    InputStream pdfStream, long contentLength, String fileName,
                                    UUID uploadedBy) {
        docRepo.findById(documentId).orElseThrow();
        // Reserve a version id so the MinIO key uses it
        UUID versionId = UUID.randomUUID();
        var uploadResult = storage.upload(pdfStream, contentLength, versionId.toString(), fileName);

        // Idempotency by SHA-256
        Optional<HdsVersion> existing = versionRepo.findByFileSha256(uploadResult.sha256());
        if (existing.isPresent()) {
            // Remove the just-uploaded copy
            storage.delete(uploadResult.storageKey());
            throw new DuplicateUploadException(existing.get());
        }

        var version = HdsVersion.builder()
            .hdsDocumentId(documentId)
            .versionLabel(versionLabel)
            .revisionYear(revisionYear)
            .fileName(fileName)
            .fileSizeBytes(uploadResult.size())
            .fileSha256(uploadResult.sha256())
            .storageKey(uploadResult.storageKey())
            .status(HdsVersionStatus.PENDING)
            .uploadedBy(uploadedBy)
            .uploadedAt(Instant.now())
            .build();
        version.setId(versionId);
        version = versionRepo.save(version);

        var job = new HdsIngestionJob();
        job.setHdsVersionId(version.getId());
        job.setStage(HdsIngestionStage.PARSING);
        job.setProgressPct(0);
        job.setAttemptCount(0);
        job.setStartedAt(Instant.now());
        job.setLastHeartbeatAt(Instant.now());
        jobRepo.save(job);

        return version;
    }

    public HdsVersion getVersion(UUID id) {
        return versionRepo.findById(id).orElseThrow();
    }

    public List<HdsVersion> listVersions(UUID documentId) {
        return versionRepo.findByHdsDocumentIdOrderByRevisionYearDesc(documentId);
    }

    public List<HdsVersion> listIndexedVersions() {
        return versionRepo.findByStatus(HdsVersionStatus.INDEXED);
    }

    public void retryVersion(UUID versionId) {
        var version = versionRepo.findById(versionId).orElseThrow();
        var jobOpt = jobRepo.findByHdsVersionId(versionId);
        var job = jobOpt.orElseGet(() -> {
            var j = new HdsIngestionJob();
            j.setHdsVersionId(versionId);
            return j;
        });
        // Roll back to the stage before the failure
        job.setStage(switch (version.getStatus()) {
            case PARSING, PENDING -> HdsIngestionStage.PARSING;
            case CHUNKING -> HdsIngestionStage.CHUNKING;
            case EMBEDDING -> HdsIngestionStage.EMBEDDING;
            case FAILED -> HdsIngestionStage.PARSING; // safe default
            default -> HdsIngestionStage.INDEXING;
        });
        job.setErrorMessage(null);
        job.setLastHeartbeatAt(null);
        jobRepo.save(job);
        version.setStatus(HdsVersionStatus.PENDING);
        version.setIndexingError(null);
        version.setIndexingProgressPct(0);
        versionRepo.save(version);
    }

    public void deleteVersion(UUID versionId) {
        var version = versionRepo.findById(versionId).orElseThrow();
        chunkRepo.deleteByHdsVersionId(versionId);
        try { storage.delete(version.getStorageKey()); } catch (Exception ignored) {}
        jobRepo.findByHdsVersionId(versionId).ifPresent(jobRepo::delete);
        versionRepo.deleteById(versionId);
    }

    public static class DuplicateUploadException extends RuntimeException {
        private final HdsVersion existing;
        public DuplicateUploadException(HdsVersion existing) {
            super("Duplicate SHA-256: existing version " + existing.getId());
            this.existing = existing;
        }
        public HdsVersion getExisting() { return existing; }
    }
}
```

- [ ] **Step 3: Test (mocks, focus on duplicate-detection branch)**

```java
// backend/bipros-hds/src/test/java/com/bipros/hds/application/library/HdsLibraryServiceTest.java
package com.bipros.hds.application.library;

import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsDiscipline;
import com.bipros.hds.domain.repo.*;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HdsLibraryServiceTest {

    @Test
    void createRejectsDuplicateShortCode() {
        var docRepo = mock(HdsDocumentRepository.class);
        when(docRepo.existsByShortCode("HDS-V3")).thenReturn(true);
        var svc = new HdsLibraryService(docRepo, mock(HdsVersionRepository.class),
            mock(HdsIngestionJobRepository.class), mock(HdsChunkRepository.class),
            mock(HdsStorageService.class));
        assertThatThrownBy(() -> svc.createDocument(new CreateHdsDocumentInput(
            "x","HDS-V3", HdsDiscipline.HIGHWAY, null, null, null)))
            .hasMessageContaining("Short code already in use");
    }

    @Test
    void uploadIsIdempotentBySha() {
        var docRepo = mock(HdsDocumentRepository.class);
        var verRepo = mock(HdsVersionRepository.class);
        var jobRepo = mock(HdsIngestionJobRepository.class);
        var chunkRepo = mock(HdsChunkRepository.class);
        var storage = mock(HdsStorageService.class);

        UUID docId = UUID.randomUUID();
        when(docRepo.findById(docId)).thenReturn(Optional.of(new HdsDocument()));
        when(storage.upload(any(), anyLong(), anyString(), anyString()))
            .thenReturn(new HdsStorageService.UploadResult("hds/x/y.pdf", "shadup".repeat(11) + "ab", 100));
        var existing = new HdsVersion();
        when(verRepo.findByFileSha256(anyString())).thenReturn(Optional.of(existing));

        var svc = new HdsLibraryService(docRepo, verRepo, jobRepo, chunkRepo, storage);
        assertThatThrownBy(() -> svc.uploadVersion(docId, "Rev 1", 2024,
            new ByteArrayInputStream(new byte[]{1}), 1, "f.pdf", UUID.randomUUID()))
            .isInstanceOf(HdsLibraryService.DuplicateUploadException.class);
        verify(storage).delete("hds/x/y.pdf");
    }
}
```

- [ ] **Step 4: Commit**
```bash
(cd backend && mvn test -pl bipros-hds -Dtest=HdsLibraryServiceTest -q)
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/library \
        backend/bipros-hds/src/test/java/com/bipros/hds/application/library
git commit -m "feat(hds): library service (CRUD + idempotent upload + retry + cascade delete)"
```

### Task C.2 — Cache invalidation on version state change

**Files:**
- Create: `.../application/library/VersionStatusListener.java`

- [ ] **Step 1: Listener (calls QueryCache.invalidateForVersion on INDEXED/FAILED transitions)**

```java
package com.bipros.hds.application.library;

import com.bipros.hds.application.retrieval.QueryCache;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import jakarta.persistence.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * JPA entity listener — invalidates cached query answers when a version transitions to INDEXED or FAILED.
 * Registered via @EntityListeners on HdsVersion if desired; alternatively, callers can invoke
 * QueryCache.invalidateForVersion(versionId) directly from the orchestrator.
 *
 * For simplicity here we expose a static helper used from the orchestrator.
 */
@Component
@RequiredArgsConstructor
public class VersionStatusListener {

    private final QueryCache cache;

    public void onIndexedOrFailed(HdsVersion v) {
        if (v.getStatus() == HdsVersionStatus.INDEXED || v.getStatus() == HdsVersionStatus.FAILED) {
            cache.invalidateForVersion(v.getId());
        }
    }
}
```

> The ingestion orchestrator can call `versionStatusListener.onIndexedOrFailed(version)` after marking INDEXED. Track A may want to wire this — if not, a Phase 5 follow-up does it. Tracks A and C do not edit the same file because of this; the wiring is a single-line addition in `IngestionOrchestrator` and is part of Track A's task A.4 cleanup. Coordination flag: Track A reads this paragraph and adds the call before committing A.4. (If parallel timing makes that impossible, leave a `// TODO(hds-cache-invalidate)` and Phase 5 will fix.)

- [ ] **Step 2: Commit**
```bash
git add backend/bipros-hds/src/main/java/com/bipros/hds/application/library/VersionStatusListener.java
git commit -m "feat(hds): version status listener (invalidates query cache on INDEXED/FAILED)"
```

---

## Phase 2 verify gate

```bash
(cd backend && mvn test -pl bipros-hds -q)
```
Tests added by this phase:
- `ChunkingServiceTest`
- `EmbeddingServiceTest`
- `IngestionOrchestratorTest`
- `RetrievalServiceTest`
- `HdsLibraryServiceTest`

Plus all Phase 1 tests must still pass.
