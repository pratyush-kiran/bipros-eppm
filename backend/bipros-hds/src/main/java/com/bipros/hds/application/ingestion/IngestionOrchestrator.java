package com.bipros.hds.application.ingestion;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.application.library.VersionStatusListener;
import com.bipros.hds.infrastructure.docling.DoclingClient;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import com.bipros.hds.infrastructure.parser.RoutingPdfParser;
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
    private final RoutingPdfParser parser;
    private final ChunkingService chunking;
    private final EmbeddingService embedding;
    private final HybridSearchRepository hybridRepo;
    private final ProgressStreamRegistry progress;
    private final VersionStatusListener versionStatusListener;

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
                try (var pdf = storage.download(version.getStorageKey())) {
                    Long sz = version.getFileSizeBytes() == null ? -1L : version.getFileSizeBytes();
                    parsed = parser.parse(pdf, sz, version.getFileName());
                }
                if (parsed.getPages() != null) {
                    Integer pages = parsed.getPages();
                    versionRepo.findById(version.getId()).ifPresent(v -> {
                        if (v.getPageCount() == null) {
                            v.setPageCount(pages);
                            versionRepo.save(v);
                        }
                    });
                }
                advance(job, version, HdsIngestionStage.PARSING, 60, "Parsed " + parsed.getPages() + " pages");
            }

            // CHUNKING
            if (resumeFrom(job, HdsIngestionStage.CHUNKING)) {
                if (parsed == null) {
                    // Resumed mid-pipeline — re-parse (Docling is idempotent on the same bytes)
                    try (var pdf = storage.download(version.getStorageKey())) {
                        long sz = version.getFileSizeBytes() == null ? -1L : version.getFileSizeBytes();
                        parsed = parser.parse(pdf, sz, version.getFileName());
                    }
                }
                advance(job, version, HdsIngestionStage.CHUNKING, 65, "Chunking…");
                chunks = chunking.chunk(parsed);
                advance(job, version, HdsIngestionStage.CHUNKING, 70, "Built " + chunks.size() + " chunks");
            }

            // EMBEDDING
            if (resumeFrom(job, HdsIngestionStage.EMBEDDING)) {
                if (chunks == null) {
                    if (parsed == null) {
                        try (var pdf = storage.download(version.getStorageKey())) {
                            long sz = version.getFileSizeBytes() == null ? -1L : version.getFileSizeBytes();
                            parsed = parser.parse(pdf, sz, version.getFileName());
                        }
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
                        try (var pdf = storage.download(version.getStorageKey())) {
                            long sz = version.getFileSizeBytes() == null ? -1L : version.getFileSizeBytes();
                            parsed = parser.parse(pdf, sz, version.getFileName());
                        }
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

                final int chunkCount = chunks.size();
                HdsVersion finalVersion = versionRepo.findById(version.getId())
                    .map(v -> {
                        v.setChunkCount(chunkCount);
                        v.setStatus(HdsVersionStatus.INDEXED);
                        v.setIndexedAt(Instant.now());
                        return versionRepo.save(v);
                    }).orElse(version);

                jobRepo.findById(job.getId()).ifPresent(j -> {
                    j.setStage(HdsIngestionStage.COMPLETE);
                    j.setProgressPct(100);
                    j.setCompletedAt(Instant.now());
                    jobRepo.save(j);
                });
                progress.publish(new IngestionProgressEvent(finalVersion.getId(), "COMPLETE", 100, "Indexed " + chunkCount + " chunks"));
                versionStatusListener.onIndexedOrFailed(finalVersion);
            }
        } catch (Exception e) {
            log.error("Ingestion failed: versionId={}", version.getId(), e);
            String errMsg = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                jobRepo.findById(job.getId()).ifPresent(j -> {
                    j.setStage(HdsIngestionStage.FAILED);
                    j.setErrorMessage(errMsg);
                    j.setCompletedAt(Instant.now());
                    jobRepo.save(j);
                });
                versionRepo.findById(version.getId()).ifPresent(v -> {
                    v.setStatus(HdsVersionStatus.FAILED);
                    v.setIndexingError(errMsg);
                    versionRepo.save(v);
                    versionStatusListener.onIndexedOrFailed(v);
                });
            } catch (Exception nested) {
                log.warn("Could not record FAILED status for job={} version={}: {}",
                    job.getId(), version.getId(), nested.getMessage());
            }
            progress.publish(new IngestionProgressEvent(version.getId(), "FAILED",
                job.getProgressPct() == null ? 0 : job.getProgressPct(), errMsg));
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
        // Reload to pick up fresh @Version values — between two advance() calls within the
        // same run(), the JPA session has closed and the local @Version is stale.
        var freshJob = jobRepo.findById(job.getId()).orElse(job);
        freshJob.setStage(stage);
        freshJob.setProgressPct(pct);
        freshJob.setLastHeartbeatAt(Instant.now());
        jobRepo.save(freshJob);
        // Copy the post-save @Version back into the caller's reference so the next
        // advance() call (which still uses the run-loop's local `job`) sees current state.
        job.setVersion(freshJob.getVersion());
        job.setStage(freshJob.getStage());
        job.setProgressPct(freshJob.getProgressPct());

        var freshVersion = versionRepo.findById(version.getId()).orElse(version);
        freshVersion.setStatus(switch (stage) {
            case PARSING -> HdsVersionStatus.PARSING;
            case CHUNKING -> HdsVersionStatus.CHUNKING;
            case EMBEDDING, INDEXING -> HdsVersionStatus.EMBEDDING;
            case COMPLETE -> HdsVersionStatus.INDEXED;
            case FAILED -> HdsVersionStatus.FAILED;
        });
        freshVersion.setIndexingProgressPct(pct);
        versionRepo.save(freshVersion);
        version.setVersion(freshVersion.getVersion());
        version.setStatus(freshVersion.getStatus());
        version.setIndexingProgressPct(freshVersion.getIndexingProgressPct());

        progress.publish(new IngestionProgressEvent(freshVersion.getId(), stage.name(), pct, msg));
    }
}
