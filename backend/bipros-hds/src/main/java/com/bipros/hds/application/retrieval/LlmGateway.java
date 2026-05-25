package com.bipros.hds.application.retrieval;

import java.util.List;

public interface LlmGateway {
    /** Returns the LLM's completion text. Used for plan/examine/verify (structured JSON outputs).
     *  Implementations should set temperature low (0.0–0.2) and request JSON-mode where supported. */
    String completeStructured(List<ChatMessage> messages, String responseFormatJsonSchemaName);

    /** Returns the full streamed answer as a single concatenated string.
     *  The caller separately publishes streaming chunks via a callback when needed. */
    String completeStreaming(List<ChatMessage> messages, StreamCallback onToken);

    record ChatMessage(String role, String content) {}

    @FunctionalInterface
    interface StreamCallback {
        void onToken(String token);
    }
}
