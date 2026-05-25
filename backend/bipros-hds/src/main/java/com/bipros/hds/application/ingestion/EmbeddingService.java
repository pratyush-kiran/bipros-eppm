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
