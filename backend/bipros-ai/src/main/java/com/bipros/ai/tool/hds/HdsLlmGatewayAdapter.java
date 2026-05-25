package com.bipros.ai.tool.hds;

import com.bipros.ai.provider.LlmProvider;
import com.bipros.hds.application.retrieval.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bridges bipros-hds's {@link LlmGateway} interface to the existing
 * {@link LlmProvider} bean used by the chat orchestrator. The provider
 * implementation (e.g. {@code OpenAiCompatibleLlmProvider}) resolves the
 * default {@code LlmProviderConfig} from the {@code ai.llm_provider_config}
 * table internally — no per-call config plumbing is required here.
 *
 * <p>Marked {@link Primary} so it overrides the {@code @Profile("test")}
 * {@code StubLlmGateway} when running with the default profile. Tests that
 * want the stub continue to activate the {@code test} profile.
 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class HdsLlmGatewayAdapter implements LlmGateway {

    /** Hard ceiling for the structured / streaming responses. Plan/examine/
     *  verify JSON outputs are small; the draft answer is bounded by the
     *  reranker top-K chunks. 4096 leaves plenty of headroom. */
    private static final int MAX_TOKENS = 4096;

    /** Plan/examine/verify must be near-deterministic. */
    private static final double STRUCTURED_TEMPERATURE = 0.0d;

    /** Draft can have mild creativity but stays grounded by the verifier loop. */
    private static final double STREAMING_TEMPERATURE = 0.2d;

    /** Streamed token-collection timeout (matches the longest tolerated
     *  reasoning budget for a draft). */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(2);

    private final LlmProvider provider;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String completeStructured(List<ChatMessage> messages, String responseFormatJsonSchemaName) {
        List<LlmProvider.Message> providerMsgs = toProviderMessages(messages);
        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                providerMsgs,
                List.of(),
                MAX_TOKENS,
                STRUCTURED_TEMPERATURE,
                null,
                jsonObjectResponseFormat(),
                null,
                null);
        LlmProvider.ChatResponse resp = retryOnTransient(() -> provider.chatCompletion(req));
        return resp.content() == null ? "" : resp.content();
    }

    @Override
    public String completeStreaming(List<ChatMessage> messages, StreamCallback onToken) {
        List<LlmProvider.Message> providerMsgs = toProviderMessages(messages);
        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                providerMsgs,
                List.of(),
                MAX_TOKENS,
                STREAMING_TEMPERATURE,
                null);

        if (provider.supportsStreaming()) {
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<RuntimeException> streamError = new AtomicReference<>();
            try {
                Flux<LlmProvider.ChatChunk> flux = provider.chatCompletionStream(req);
                flux.toStream(64)
                        .forEach(chunk -> {
                            String delta = chunk == null ? null : chunk.delta();
                            if (delta == null || delta.isEmpty()) return;
                            accumulated.append(delta);
                            if (onToken != null) {
                                try {
                                    onToken.onToken(delta);
                                } catch (RuntimeException cbEx) {
                                    log.warn("HDS stream callback threw: {}", cbEx.getMessage());
                                }
                            }
                        });
            } catch (RuntimeException e) {
                streamError.set(e);
            }

            if (streamError.get() == null && !accumulated.isEmpty()) {
                return accumulated.toString();
            }

            // Streaming path failed or yielded nothing — fall through to the
            // non-streaming completion so the retrieval loop can still answer.
            log.warn("HDS streaming completion empty or errored; falling back to non-streaming. error={}",
                    streamError.get() == null ? "none" : streamError.get().getMessage());
        }

        LlmProvider.ChatResponse resp = retryOnTransient(() -> provider.chatCompletion(req));
        String content = resp.content() == null ? "" : resp.content();
        if (onToken != null && !content.isEmpty()) {
            try {
                onToken.onToken(content);
            } catch (RuntimeException cbEx) {
                log.warn("HDS fallback callback threw: {}", cbEx.getMessage());
            }
        }
        return content;
    }

    private List<LlmProvider.Message> toProviderMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(m -> new LlmProvider.Message(m.role(), m.content()))
                .collect(Collectors.toList());
    }

    /**
     * Returns an OpenAI-style {@code response_format} requesting JSON output.
     * The HDS retrieval prompts already include JSON shape instructions; this
     * just tightens the contract for providers that honour the hint.
     */
    private com.fasterxml.jackson.databind.JsonNode jsonObjectResponseFormat() {
        ObjectNode rf = om.createObjectNode();
        rf.put("type", "json_object");
        return rf;
    }

    @SuppressWarnings("unused")
    private Duration streamTimeoutForTests() {
        return STREAM_TIMEOUT;
    }

    /**
     * Retries the provider call up to twice on transient network failures
     * ("Connection reset", "Connection prematurely closed", "Broken pipe", etc.)
     * with a short backoff. Pool connections to OpenAI go stale; the first
     * attempt may fail with a reset before the client picks a fresh socket.
     */
    private <T> T retryOnTransient(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isTransientNetworkError(e) || attempt == 3) {
                    throw e;
                }
                last = e;
                long delayMs = 300L * attempt;
                log.warn("LLM call attempt {}/3 hit transient error ({}); retrying in {}ms",
                        attempt, e.getMessage(), delayMs);
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last == null ? new IllegalStateException("unreachable") : last;
    }

    static boolean isTransientNetworkError(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && (
                msg.contains("Connection reset")
                || msg.contains("Connection prematurely closed")
                || msg.contains("Broken pipe")
                || msg.contains("connection was aborted")
                || msg.contains("Read timed out")
            )) return true;
            if (cur instanceof java.net.SocketException
                || cur instanceof java.net.SocketTimeoutException
                || cur instanceof java.io.IOException
                   && cur.getClass().getSimpleName().equals("PrematureCloseException")) {
                return true;
            }
        }
        return false;
    }
}
