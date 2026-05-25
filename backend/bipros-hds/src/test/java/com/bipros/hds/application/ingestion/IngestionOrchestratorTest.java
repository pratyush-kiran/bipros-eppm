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
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.bipros.hds.infrastructure.parser.RoutingPdfParser;
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
        RoutingPdfParser parser = mock(RoutingPdfParser.class);
        ChunkingService chunking = new ChunkingService();
        EmbeddingClient embedClient = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> inputs) {
                return inputs.stream().map(s -> new float[]{1f, 2f, 3f}).toList();
            }
            @Override public int dim() { return 3; }
        };
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
        try {
            when(parser.parse(any(), anyLong(), any())).thenReturn(doclingResp);
        } catch (java.io.IOException e) { throw new AssertionError(e); }

        var job = new HdsIngestionJob();
        job.setId(UUID.randomUUID());
        job.setHdsVersionId(versionId);
        job.setStage(HdsIngestionStage.PARSING);

        var listener = mock(com.bipros.hds.application.library.VersionStatusListener.class);
        var orch = new IngestionOrchestrator(props, versionRepo, jobRepo, storage, docling,
            parser, chunking, embedSvc, hybrid, progress, listener);
        orch.run(job);

        ArgumentCaptor<HdsVersion> verCap = ArgumentCaptor.forClass(HdsVersion.class);
        verify(versionRepo, atLeastOnce()).save(verCap.capture());
        var finalState = verCap.getAllValues().get(verCap.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(HdsVersionStatus.INDEXED);

        verify(hybrid).insertChunks(any(), any());
    }
}
