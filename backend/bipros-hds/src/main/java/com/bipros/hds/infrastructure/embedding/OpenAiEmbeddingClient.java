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

/**
 * Embedding client that posts batched inputs to an OpenAI-compatible
 * {@code /embeddings} endpoint. The dimension is driven by
 * {@code bipros.hds.embedding.dimensions} (default 1536 for
 * {@code text-embedding-3-large}).
 *
 * <p>Open question: the existing {@code bipros-ai} module owns the
 * encrypted provider API key infrastructure ({@code LlmProviderConfig}).
 * Phase 1 keeps this client self-contained — the API key falls back to
 * the {@code OPENAI_API_KEY} env var so the module compiles in isolation.
 * If {@code bipros-ai} exposes an {@code embed()} method on its
 * {@code LlmProvider} bean, Phase 2 should rewire this to delegate.</p>
 */
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
