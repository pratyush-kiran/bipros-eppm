package com.bipros.hds.application.retrieval;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.domain.repo.HdsQueryLogRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.bipros.hds.infrastructure.reranker.NoopReranker;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    @Test
    void safeFailsWhenNoChunksFound() {
        var props = new HdsProperties();
        props.getReranker().setTopK(10);
        props.getRetrieval().setMaxChunksPerQuery(20);
        props.getRetrieval().setMaxRounds(1);
        props.getRetrieval().setSimilarityFloor(0.3);
        props.getRetrieval().setVectorTopK(50);
        props.getRetrieval().setBm25TopK(50);
        props.getRetrieval().setCacheTtlSeconds(60);
        props.getVerifier().setMaxRetries(1);

        var versionRepo = mock(HdsVersionRepository.class);
        var logRepo = mock(HdsQueryLogRepository.class);
        var hybrid = mock(HybridSearchRepository.class);
        var embed = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> inputs) {
                return inputs.stream().map(s -> new float[]{0f}).toList();
            }
            @Override public int dim() { return 1; }
        };
        var llm = new StubLlmGateway();
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        var cache = new QueryCache(redis);

        var version = new HdsVersion();
        UUID vid = UUID.randomUUID();
        version.setId(vid);
        version.setVersionLabel("Rev 1");
        when(versionRepo.findAllById(List.of(vid))).thenReturn(List.of(version));
        when(hybrid.searchByEmbedding(any(), any(), anyDouble(), anyInt())).thenReturn(List.of());
        when(hybrid.searchByKeyword(anyString(), any(), anyInt())).thenReturn(List.of());

        var svc = new RetrievalService(props, versionRepo, logRepo, hybrid, embed, new NoopReranker(), llm, cache);
        var ans = svc.answer("anything", List.of(vid), 1, UUID.randomUUID(), UUID.randomUUID(), null);
        assertThat(ans.answer()).contains("I don't see that");
        assertThat(ans.citations()).isEmpty();
    }

    @Test
    void overviewIntentSamplesStructurallyAndSkipsVectorSearch() {
        // The planner classifies "what is in this document?" as OVERVIEW.
        // The service must call sampleOverviewChunks (not searchByEmbedding/Keyword).
        var props = defaultProps();
        var versionRepo = mock(HdsVersionRepository.class);
        var hybrid = mock(HybridSearchRepository.class);
        var version = new HdsVersion();
        UUID vid = UUID.randomUUID();
        version.setId(vid);
        version.setVersionLabel("Rev 1");
        when(versionRepo.findAllById(List.of(vid))).thenReturn(List.of(version));

        UUID chunkId = UUID.randomUUID();
        when(hybrid.sampleOverviewChunks(List.of(vid), 10)).thenReturn(List.of(chunkId));
        when(hybrid.fetchChunks(List.of(chunkId))).thenReturn(List.of(
            new HybridSearchRepository.ChunkRow(
                chunkId, vid, 0, 1, 1,
                "HDS Vol 3 > 1. Introduction", "1",
                HdsChunkType.TEXT, "Intro text", 4)));

        var svc = buildService(props, versionRepo, hybrid, overviewIntentGateway());
        var ans = svc.answer(
            "What information is in this document?",
            List.of(vid), 1, UUID.randomUUID(), UUID.randomUUID(), null);

        verify(hybrid, atLeastOnce()).sampleOverviewChunks(List.of(vid), 10);
        verify(hybrid, never()).searchByEmbedding(any(), any(), anyDouble(), anyInt());
        verify(hybrid, never()).searchByKeyword(anyString(), any(), anyInt());
        assertThat(ans.metadata().get("intent")).isEqualTo("overview");
    }

    @Test
    void offTopicIntentReturnsPoliteRefusalWithoutRetrieval() {
        var props = defaultProps();
        var versionRepo = mock(HdsVersionRepository.class);
        var hybrid = mock(HybridSearchRepository.class);
        var version = new HdsVersion();
        UUID vid = UUID.randomUUID();
        version.setId(vid);
        version.setVersionLabel("Rev 1");
        when(versionRepo.findAllById(List.of(vid))).thenReturn(List.of(version));

        var svc = buildService(props, versionRepo, hybrid, offTopicIntentGateway());
        var ans = svc.answer("hi", List.of(vid), 1, UUID.randomUUID(), UUID.randomUUID(), null);

        assertThat(ans.answer()).contains("HDS document assistant");
        assertThat(ans.citations()).isEmpty();
        assertThat(ans.metadata().get("intent")).isEqualTo("off_topic");
        verify(hybrid, never()).sampleOverviewChunks(any(), anyInt());
        verify(hybrid, never()).searchByEmbedding(any(), any(), anyDouble(), anyInt());
        verify(hybrid, never()).searchByKeyword(anyString(), any(), anyInt());
    }

    @Test
    void planParseFailureFallsBackToOverviewHeuristic() {
        // When the LLM produces malformed JSON, the service falls back via
        // looksLikeOverview() — "what information are there" should hit OVERVIEW.
        var props = defaultProps();
        var versionRepo = mock(HdsVersionRepository.class);
        var hybrid = mock(HybridSearchRepository.class);
        var version = new HdsVersion();
        UUID vid = UUID.randomUUID();
        version.setId(vid);
        version.setVersionLabel("Rev 1");
        when(versionRepo.findAllById(List.of(vid))).thenReturn(List.of(version));

        UUID chunkId = UUID.randomUUID();
        when(hybrid.sampleOverviewChunks(eqList(vid), anyInt())).thenReturn(List.of(chunkId));
        when(hybrid.fetchChunks(List.of(chunkId))).thenReturn(List.of(
            new HybridSearchRepository.ChunkRow(
                chunkId, vid, 0, 1, 1, "Doc > 1. Intro", "1",
                HdsChunkType.TEXT, "Intro", 1)));

        var svc = buildService(props, versionRepo, hybrid, malformedPlanGateway());
        var ans = svc.answer(
            "what information are there?",
            List.of(vid), 1, UUID.randomUUID(), UUID.randomUUID(), null);

        verify(hybrid, atLeastOnce()).sampleOverviewChunks(any(), anyInt());
        verify(hybrid, never()).searchByEmbedding(any(), any(), anyDouble(), anyInt());
        assertThat(ans.metadata().get("intent")).isEqualTo("overview");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static HdsProperties defaultProps() {
        var p = new HdsProperties();
        p.getReranker().setTopK(10);
        p.getRetrieval().setMaxChunksPerQuery(10);
        p.getRetrieval().setMaxRounds(1);
        p.getRetrieval().setSimilarityFloor(0.3);
        p.getRetrieval().setVectorTopK(50);
        p.getRetrieval().setBm25TopK(50);
        p.getRetrieval().setCacheTtlSeconds(60);
        p.getVerifier().setMaxRetries(0);
        return p;
    }

    private static RetrievalService buildService(HdsProperties props,
                                                  HdsVersionRepository versionRepo,
                                                  HybridSearchRepository hybrid,
                                                  LlmGateway llm) {
        var logRepo = mock(HdsQueryLogRepository.class);
        var embed = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> inputs) {
                return inputs.stream().map(s -> new float[]{0f}).toList();
            }
            @Override public int dim() { return 1; }
        };
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        var cache = new QueryCache(redis);
        return new RetrievalService(props, versionRepo, logRepo, hybrid, embed,
            new NoopReranker(), llm, cache);
    }

    private static LlmGateway overviewIntentGateway() {
        return new LlmGateway() {
            @Override public String completeStructured(List<ChatMessage> messages, String fmt) {
                String sys = messages.isEmpty() ? "" : messages.get(0).content();
                if (sys.contains("verifier")) {
                    return "{\"passed\":true,\"issues\":[]}";
                }
                return "{\"intent\":\"overview\",\"is_compound\":false," +
                    "\"sub_questions\":[],\"search_queries\":[]}";
            }
            @Override public String completeStreaming(List<ChatMessage> messages, StreamCallback cb) {
                String t = "- HDS Vol 3 > 1. Introduction — intro [c1]";
                if (cb != null) cb.onToken(t);
                return t;
            }
        };
    }

    private static LlmGateway offTopicIntentGateway() {
        return new LlmGateway() {
            @Override public String completeStructured(List<ChatMessage> messages, String fmt) {
                return "{\"intent\":\"off_topic\",\"is_compound\":false," +
                    "\"sub_questions\":[],\"search_queries\":[]}";
            }
            @Override public String completeStreaming(List<ChatMessage> messages, StreamCallback cb) {
                if (cb != null) cb.onToken("unused");
                return "unused";
            }
        };
    }

    private static LlmGateway malformedPlanGateway() {
        return new LlmGateway() {
            @Override public String completeStructured(List<ChatMessage> messages, String fmt) {
                String sys = messages.isEmpty() ? "" : messages.get(0).content();
                if (sys.contains("verifier")) {
                    return "{\"passed\":true,\"issues\":[]}";
                }
                if (sys.contains("retrieval planner")) {
                    return "this-is-not-json";   // forces the heuristic fallback
                }
                return "{\"sufficient\":true,\"follow_up_queries\":[]}";
            }
            @Override public String completeStreaming(List<ChatMessage> messages, StreamCallback cb) {
                String t = "Per [c1].";
                if (cb != null) cb.onToken(t);
                return t;
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> List<T> eqList(T item) {
        return (List) org.mockito.ArgumentMatchers.eq(List.of(item));
    }
}
