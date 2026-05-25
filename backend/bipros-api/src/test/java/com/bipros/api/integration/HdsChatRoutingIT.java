package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.hds.application.retrieval.Citation;
import com.bipros.hds.application.retrieval.RetrievalAnswer;
import com.bipros.hds.application.retrieval.RetrievalService;
import com.bipros.hds.application.retrieval.VerifyResult;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for the HDS deterministic-routing branch in
 * {@link com.bipros.ai.orchestrator.AiOrchestrator}.
 *
 * <p>Confirms three things end-to-end:
 * <ol>
 *   <li>{@code ChatRequest} parses the new {@code hdsVersionIds} field.</li>
 *   <li>When that field is non-empty, the orchestrator skips the LLM tool-
 *       selection loop and invokes {@link RetrievalService} directly
 *       (via the {@code search_hds_standards} tool registered by Track B).</li>
 *   <li>The chat response surfaces the canned retrieval answer text — proving
 *       the events from the deterministic branch are accumulated correctly.</li>
 * </ol>
 *
 * <p>The retrieval service is mocked so the test is hermetic — no embedding /
 * LLM / vector database calls fire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("HDS Chat Routing Integration")
class HdsChatRoutingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private RetrievalService retrievalService;

    private String token;

    @BeforeEach
    void setUp() {
        String suffix = "HDS" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "hdsuser" + suffix, "hdsuser" + suffix + "@example.com",
                "testPassword123!", "HDS", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("hdsuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");
    }

    @Test
    @DisplayName("chat with non-empty hdsVersionIds routes to search_hds_standards tool")
    void chatWithHdsScopeInvokesSearchTool() {
        UUID chunkId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String cannedAnswer = "Shoulder width minimum is 2.5 m on rural highways [c1].";
        when(retrievalService.answer(anyString(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new RetrievalAnswer(
                        cannedAnswer,
                        List.of(new Citation("c1", chunkId, versionId, "HDS-V3 Rev 1",
                                "Vol 3 > 4.3 > Cross-section", 87, 87, "Minimum shoulder width is 2.5 m.")),
                        new VerifyResult(true, List.of()),
                        Map.of("duration_ms", 1234)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        String body = """
                {
                  "message": "What is the minimum shoulder width?",
                  "module": "general",
                  "projectId": null,
                  "hdsVersionIds": ["%s"]
                }
                """.formatted(versionId);

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<ApiResponse> response = restTemplate.exchange(
                "/v1/ai/chat", HttpMethod.POST, request, ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().data();
        assertThat(data).containsKey("text");
        String text = (String) data.get("text");
        // The deterministic branch emits the retrieval answer as a token event,
        // which the controller accumulates into the response text. The exact
        // string match proves the HDS branch fired (the LLM path would have
        // produced something else).
        assertThat(text).contains("Shoulder width minimum");
    }

    @Test
    @DisplayName("chat without hdsVersionIds parses field as optional")
    void chatWithoutHdsScopeParsesNullField() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        // Body explicitly omits hdsVersionIds — confirms it's optional on the wire.
        String body = """
                {
                  "message": "hello",
                  "module": "general",
                  "projectId": null
                }
                """;

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<ApiResponse> response = restTemplate.exchange(
                "/v1/ai/chat", HttpMethod.POST, request, ApiResponse.class);

        // The standard path runs — it may fail downstream for unrelated reasons
        // (no LLM provider configured) but the JSON parse must not fail. So we
        // accept any status that is NOT 400 BAD_REQUEST (which is what a
        // payload binding failure would produce).
        assertThat(response.getStatusCode().value())
                .as("ChatRequest must parse JSON without hdsVersionIds")
                .isNotEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
