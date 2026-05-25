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
