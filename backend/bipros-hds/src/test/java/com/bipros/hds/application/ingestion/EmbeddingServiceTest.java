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
