package com.bipros.ai.tool.hds;

import com.bipros.ai.provider.LlmProviderConfig;
import com.bipros.ai.provider.LlmProviderConfigRepository;
import com.bipros.ai.provider.crypto.ApiKeyCipher;
import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * EmbeddingClient implementation that reuses the encrypted LlmProviderConfig
 * (same provider config the chat orchestrator uses). Marked @Primary so it
 * overrides the env-var-based OpenAiEmbeddingClient in bipros-hds.
 *
 * <p>Resolves the default provider, decrypts its API key with the same cipher
 * the chat path uses, then calls OpenAI-compatible /embeddings.
 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class BiprosAiEmbeddingClient implements EmbeddingClient {

    private final HdsProperties props;
    private final LlmProviderConfigRepository providerRepository;
    private final ApiKeyCipher apiKeyCipher;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public int dim() {
        return props.getEmbedding().getDimensions();
    }

    @Override
    public List<float[]> embedBatch(List<String> inputs) {
        if (inputs.isEmpty()) return List.of();

        LlmProviderConfig config = providerRepository.findByIsDefaultTrue()
            .orElseThrow(() -> new IllegalStateException(
                "No default LLM provider configured — set one via /v1/admin/llm-providers before ingesting HDS"));
        String apiKey;
        try {
            apiKey = apiKeyCipher.decrypt(config.getApiKeyIv(),
                                           config.getApiKeyCiphertext(),
                                           config.getApiKeyVersion());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Failed to decrypt provider API key (BIPROS_AI_KEK mismatch?). Re-save the provider config.", e);
        }

        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }

        WebClient wc = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
            .build();

        ObjectNode req = om.createObjectNode();
        req.put("model", props.getEmbedding().getModel());
        req.put("dimensions", props.getEmbedding().getDimensions());
        var arr = req.putArray("input");
        inputs.forEach(arr::add);

        JsonNode resp = wc.post()
            .uri("/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .retryWhen(reactor.util.retry.Retry.backoff(4, Duration.ofSeconds(2))
                .filter(t -> isRetryable(t) || HdsLlmGatewayAdapter.isTransientNetworkError(t)))
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
