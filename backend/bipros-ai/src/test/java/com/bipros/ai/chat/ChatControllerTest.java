package com.bipros.ai.chat;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.context.AiContextResolver;
import com.bipros.ai.orchestrator.AiOrchestrator;
import com.bipros.ai.provider.LlmProviderConfig;
import com.bipros.ai.provider.LlmProviderConfigRepository;
import com.bipros.ai.provider.OpenAiCompatibleLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security invariant test for {@link ChatController}: reloading a project-
 * scoped conversation while browsing in general mode MUST keep the
 * conversation's original scope. The fix re-resolves the context from the
 * conversation's stored projectId/module when the request's projectId is
 * null and the conversation has a stored projectId.
 */
class ChatControllerTest {

    private ConversationService conversationService;
    private OpenAiCompatibleLlmProvider llmProvider;
    private AiOrchestrator orchestrator;
    private AiContextResolver contextResolver;
    private LlmProviderConfigRepository llmProviderConfigRepository;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        conversationService = Mockito.mock(ConversationService.class);
        llmProvider = Mockito.mock(OpenAiCompatibleLlmProvider.class);
        orchestrator = Mockito.mock(AiOrchestrator.class);
        contextResolver = Mockito.mock(AiContextResolver.class);
        llmProviderConfigRepository = Mockito.mock(LlmProviderConfigRepository.class);
        controller = new ChatController(conversationService, llmProvider, orchestrator,
                contextResolver, llmProviderConfigRepository);

        LlmProviderConfig cfg = new LlmProviderConfig();
        cfg.setMaxTokens(1024);
        cfg.setTimeoutMs(30000);
        when(llmProviderConfigRepository.findByIsDefaultTrueAndIsActiveTrue())
                .thenReturn(Optional.of(cfg));
    }

    @Test
    void reloadingProjectScopedConversationInGeneralModePreservesScope() {
        UUID convId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Request: existing conversation, projectId=null (general mode page).
        ChatController.ChatRequest req = new ChatController.ChatRequest(
                convId, null, "general", "hi", null, null);

        // First resolve() call (from request) returns a null-projectId context.
        AiContext generalCtx = new AiContext(userId, null, "general", "USER", "PM", List.of(p1));
        // Conversation is stored with project scope to p1 / "cost".
        AiConversation conv = new AiConversation();
        conv.setId(convId);
        conv.setProjectId(p1);
        conv.setModule("cost");
        // Second resolve() call (from conversation) returns the pinned context.
        AiContext pinnedCtx = new AiContext(userId, p1, "cost", "USER", "PM", List.of(p1));

        when(contextResolver.resolve(null, "general")).thenReturn(generalCtx);
        when(conversationService.getOrCreate(convId, generalCtx)).thenReturn(conv);
        when(contextResolver.resolve(p1, "cost")).thenReturn(pinnedCtx);
        when(conversationService.getMessages(convId)).thenReturn(List.of());
        when(orchestrator.handle(any(), any(), anyList(), any(AiContext.class), any(), any()))
                .thenReturn(Flux.empty());

        controller.chat(req);

        // The orchestrator MUST be invoked with the pinned (project-scoped) context,
        // not the general one — otherwise an old project-scoped conversation gets
        // silently broadened on reload.
        ArgumentCaptor<AiContext> ctxCaptor = ArgumentCaptor.forClass(AiContext.class);
        verify(orchestrator).handle(any(), any(), anyList(), ctxCaptor.capture(), any(), any());
        assertEquals(p1, ctxCaptor.getValue().projectId(),
                "Project-scoped conversation reloaded in general mode lost its scope");
        assertEquals("cost", ctxCaptor.getValue().module());
    }

    @Test
    void newConversationInGeneralModeStaysInGeneralMode() {
        UUID userId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();

        // No conversationId → brand-new conversation, no rebind should happen.
        ChatController.ChatRequest req = new ChatController.ChatRequest(
                null, null, "general", "list my projects", null, null);

        AiContext generalCtx = new AiContext(userId, null, "general", "USER", "PM", List.of(p1));
        AiConversation conv = new AiConversation();
        conv.setId(UUID.randomUUID());
        conv.setProjectId(null);
        conv.setModule("general");

        when(contextResolver.resolve(null, "general")).thenReturn(generalCtx);
        when(conversationService.getOrCreate(null, generalCtx)).thenReturn(conv);
        when(conversationService.getMessages(conv.getId())).thenReturn(List.of());
        when(orchestrator.handle(any(), any(), anyList(), any(AiContext.class), any(), any()))
                .thenReturn(Flux.empty());

        controller.chat(req);

        ArgumentCaptor<AiContext> ctxCaptor = ArgumentCaptor.forClass(AiContext.class);
        verify(orchestrator).handle(any(), any(), anyList(), ctxCaptor.capture(), any(), any());
        assertEquals(null, ctxCaptor.getValue().projectId(),
                "General-mode session must remain unpinned");
        // Re-resolve must NOT be triggered by a brand-new conversation.
        verify(contextResolver, never()).resolve(eq(p1), any());
    }
}
