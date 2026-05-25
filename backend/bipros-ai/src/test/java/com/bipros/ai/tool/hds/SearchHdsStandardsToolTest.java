package com.bipros.ai.tool.hds;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.testsupport.AiContextFixtures;
import com.bipros.ai.tool.ToolResult;
import com.bipros.hds.application.retrieval.Citation;
import com.bipros.hds.application.retrieval.RetrievalAnswer;
import com.bipros.hds.application.retrieval.RetrievalService;
import com.bipros.hds.application.retrieval.VerifyResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the JSON-node payload {@link SearchHdsStandardsTool} emits and the
 * boundary handling around the {@code selected_version_ids} input. The retrieval
 * service is mocked — this is a pure shape test.
 */
@DisplayName("SearchHdsStandardsTool — shape + input validation")
class SearchHdsStandardsToolTest {

    private RetrievalService retrieval;
    private SearchHdsStandardsTool tool;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        tool = new SearchHdsStandardsTool(retrieval);
    }

    @Test
    @DisplayName("returns answer text + citation array on happy path")
    void returnsAnswerAndCitations() throws Exception {
        UUID chunkId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(retrieval.answer(anyString(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new RetrievalAnswer(
                        "Per the spec, the answer is X [c1].",
                        List.of(new Citation("c1", chunkId, versionId, "HDS-V3 Rev 1",
                                "Vol 3 > 4.3", 87, 87, "excerpt-text")),
                        new VerifyResult(true, List.of()),
                        Map.of("duration_ms", 1234, "rounds", 2)));

        JsonNode input = om.readTree("""
                {"question":"shoulder width","selected_version_ids":["%s"],"max_rounds":1}
                """.formatted(versionId));

        AiContext ctx = AiContextFixtures.forProfile("PROJECT_MANAGER", null);
        ToolResult result = tool.execute(input, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("[c1]");

        JsonNode data = result.data();
        assertThat(data).isNotNull();
        assertThat(data.get("answer").asText()).contains("[c1]");
        assertThat(data.get("verifier_passed").asBoolean()).isTrue();

        JsonNode citations = data.get("citations");
        assertThat(citations.isArray()).isTrue();
        assertThat(citations).hasSize(1);
        JsonNode c0 = citations.get(0);
        assertThat(c0.get("marker").asText()).isEqualTo("c1");
        assertThat(c0.get("chunk_id").asText()).isEqualTo(chunkId.toString());
        assertThat(c0.get("version_id").asText()).isEqualTo(versionId.toString());
        assertThat(c0.get("version_label").asText()).isEqualTo("HDS-V3 Rev 1");
        assertThat(c0.get("section_path").asText()).isEqualTo("Vol 3 > 4.3");
        assertThat(c0.get("page_start").asInt()).isEqualTo(87);
        assertThat(c0.get("page_end").asInt()).isEqualTo(87);
        assertThat(c0.get("excerpt").asText()).isEqualTo("excerpt-text");

        JsonNode meta = data.get("metadata");
        assertThat(meta).isNotNull();
        assertThat(meta.get("duration_ms").asInt()).isEqualTo(1234);
        assertThat(meta.get("rounds").asInt()).isEqualTo(2);

        // userId propagates from AiContext; conversationId is null (TODO).
        verify(retrieval).answer(eq("shoulder width"), any(), eq(1), eq(ctx.userId()), eq(null), eq(null));
    }

    @Test
    @DisplayName("empty question → structured error")
    void rejectsEmptyQuestion() throws Exception {
        JsonNode input = om.readTree("""
                {"question":"   ","selected_version_ids":["%s"]}
                """.formatted(UUID.randomUUID()));

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("PROJECT_MANAGER", null));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("question");
    }

    @Test
    @DisplayName("empty selected_version_ids → structured error")
    void rejectsEmptyVersionIds() throws Exception {
        JsonNode input = om.readTree("""
                {"question":"shoulder width","selected_version_ids":[]}
                """);

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("PROJECT_MANAGER", null));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("at least one version");
    }

    @Test
    @DisplayName("malformed UUID → structured error (does not call retrieval)")
    void rejectsMalformedUuid() throws Exception {
        JsonNode input = om.readTree("""
                {"question":"shoulder width","selected_version_ids":["not-a-uuid"]}
                """);

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("PROJECT_MANAGER", null));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid version UUID");
    }

    @Test
    @DisplayName("default max_rounds = 2 when omitted")
    void defaultsMaxRoundsToTwo() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(retrieval.answer(anyString(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new RetrievalAnswer("ok", List.of(),
                        new VerifyResult(true, List.of()), Map.of()));

        JsonNode input = om.readTree("""
                {"question":"q","selected_version_ids":["%s"]}
                """.formatted(versionId));

        tool.execute(input, AiContextFixtures.forProfile("PROJECT_MANAGER", null));

        verify(retrieval).answer(eq("q"), any(), eq(2), any(), any(), any());
    }
}
