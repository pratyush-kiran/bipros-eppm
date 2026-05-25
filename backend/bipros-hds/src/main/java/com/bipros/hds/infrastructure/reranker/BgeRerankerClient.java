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
import java.util.List;

/**
 * Calls a BGE-reranker-v2-m3 HTTP service. The service contract:
 * <pre>
 *   POST /rerank  {"query":"...","documents":["...","..."],"top_k":10}
 *    -&gt; {"results":[{"index":3,"score":0.97}, ...]}  (already top_k, sorted)
 * </pre>
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
