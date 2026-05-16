package com.bipros.ai.chat;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.provider.LlmProvider;
import com.bipros.ai.provider.LlmProviderConfigRepository;
import com.bipros.ai.provider.OpenAiCompatibleLlmProvider;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/v1/ai")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ConversationService conversationService;
    private final OpenAiCompatibleLlmProvider llmProvider;
    private final com.bipros.ai.orchestrator.AiOrchestrator orchestrator;
    private final com.bipros.ai.context.AiContextResolver contextResolver;
    private final com.bipros.ai.provider.LlmProviderConfigRepository llmProviderConfigRepository;

    @PostMapping("/chat")
    @PreAuthorize("@aiAccess.canChat(#request.projectId)")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        AiContext ctx = contextResolver.resolve(request.projectId(), request.module());
        var conv = conversationService.getOrCreate(request.conversationId(), ctx);
        // Security invariant: reloading an old conversation that was originally
        // project-scoped MUST keep that scope, even if the caller sent
        // projectId=null (e.g. browsing a different page in general mode).
        // Never silently broaden a project-scoped conversation.
        if (request.conversationId() != null
                && conv.getProjectId() != null
                && ctx.projectId() == null) {
            ctx = contextResolver.resolve(conv.getProjectId(), conv.getModule());
        }
        List<LlmProvider.Message> history = conversationService.getMessages(conv.getId());

        // Persist the user message BEFORE invoking the orchestrator so a thrown
        // orchestrator (LLM outage, timeout, tool error escaping the loop) does
        // not lose the user's message from conversation history. The streaming
        // endpoint already does this in the same order — keep them aligned.
        conversationService.appendUserMessage(conv.getId(), request.message());

        var flux = orchestrator.handle(request.message(), request.imageUrl(), history, ctx, llmProvider,
                resolveConfig());
        List<com.bipros.ai.orchestrator.AiOrchestrator.ChatEvent> events = flux.collectList().block();

        StringBuilder text = new StringBuilder();
        if (events != null) {
            for (var e : events) {
                if ("token".equals(e.event()) && e.data().get("delta") != null) {
                    text.append(e.data().get("delta"));
                }
                if ("done".equals(e.event()) && e.data().get("text") != null) {
                    text.append(e.data().get("text"));
                }
            }
        }

        conversationService.appendAssistantMessage(conv.getId(), text.toString());

        return ResponseEntity.ok(ApiResponse.ok(new ChatResponse(conv.getId(), text.toString())));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@aiAccess.canChat(#request.projectId)")
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        AiContext ctx = contextResolver.resolve(request.projectId(), request.module());
        var conv = conversationService.getOrCreate(request.conversationId(), ctx);
        // Security invariant: see /chat — reloading a project-scoped conversation
        // in general mode must NOT silently broaden the scope.
        if (request.conversationId() != null
                && conv.getProjectId() != null
                && ctx.projectId() == null) {
            ctx = contextResolver.resolve(conv.getProjectId(), conv.getModule());
        }
        List<LlmProvider.Message> history = conversationService.getMessages(conv.getId());

        conversationService.appendUserMessage(conv.getId(), request.message());

        // Emit the conversation id as the very first SSE frame so the client
        // can persist it and send it back on the next turn (the streaming
        // endpoint has no response body to return ChatResponse through).
        org.springframework.http.codec.ServerSentEvent<String> startedEvent =
                org.springframework.http.codec.ServerSentEvent.<String>builder()
                        .event("conversation_started")
                        .data("{\"conversationId\":\"" + conv.getId() + "\"}")
                        .build();

        StringBuilder accumulated = new StringBuilder();
        Flux<org.springframework.http.codec.ServerSentEvent<String>> body = orchestrator
                .handle(request.message(), request.imageUrl(), history, ctx, llmProvider, resolveConfig())
                .doOnNext(event -> {
                    if ("done".equals(event.event()) && event.data().get("text") != null) {
                        accumulated.setLength(0);
                        accumulated.append(event.data().get("text").toString());
                    } else if ("token".equals(event.event()) && accumulated.length() == 0
                            && event.data().get("delta") != null) {
                        accumulated.append(event.data().get("delta").toString());
                    }
                })
                .map(event -> {
                    String json;
                    try {
                        json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event.data());
                    } catch (Exception e) {
                        json = "{}";
                    }
                    return org.springframework.http.codec.ServerSentEvent.<String>builder()
                            .event(event.event())
                            .data(json)
                            .build();
                })
                .doOnComplete(() -> conversationService.appendAssistantMessage(
                        conv.getId(), accumulated.length() > 0 ? accumulated.toString() : "(empty)"));

        return Flux.concat(Flux.just(startedEvent), body);
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationDto>>> listConversations(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(conversationService.list(projectId, limit)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<ConversationDetailDto>> getConversation(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(conversationService.getDetail(id)));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(@PathVariable UUID id) {
        conversationService.softDelete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private com.bipros.ai.provider.LlmProviderConfig resolveConfig() {
        return llmProviderConfigRepository.findByIsDefaultTrueAndIsActiveTrue()
                .or(llmProviderConfigRepository::findFirstByIsActiveTrueOrderByIsDefaultDescCreatedAtAsc)
                .orElseThrow(() -> new IllegalStateException("No active LLM provider configured. Add one via /v1/admin/llm-providers."));
    }

    public record ChatRequest(UUID conversationId, UUID projectId, String module, String message, String imageUrl) {
    }

    public record ChatResponse(UUID conversationId, String text) {
    }

    public record ConversationDto(UUID id, String title, String module, Instant lastMessageAt) {
    }

    public record ConversationDetailDto(UUID id, String title, UUID projectId, String module, List<MessageDto> messages) {
    }

    public record MessageDto(String role, String content, Instant createdAt) {
    }
}
