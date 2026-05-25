package com.bipros.hds.infrastructure.reranker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Default reranker used when {@code bipros.hds.reranker.enabled} is missing
 * or {@code false}. Preserves the upstream order and caps at topK.
 */
@Component
@ConditionalOnProperty(name = "bipros.hds.reranker.enabled", havingValue = "false", matchIfMissing = true)
public class NoopReranker implements Reranker {
    @Override
    public List<Integer> rerank(String query, List<String> candidates, int topK) {
        return IntStream.range(0, Math.min(topK, candidates.size())).boxed().toList();
    }
}
