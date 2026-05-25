package com.bipros.ai.chat;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.provider.LlmProvider;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final SecurityContextHelper securityContextHelper;

    @Transactional
    public AiConversation getOrCreate(UUID conversationId, AiContext ctx) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId.toString()));
        }
        AiConversation conv = new AiConversation();
        conv.setId(UUID.randomUUID());
        conv.setUserId(ctx.userId());
        conv.setProjectId(ctx.projectId());
        conv.setModule(ctx.module());
        conv.setTitle("Chat");
        conv.setStartedAt(Instant.now());
        conv.setLastMessageAt(Instant.now());
        return conversationRepository.save(conv);
    }

    @Transactional(readOnly = true)
    public List<LlmProvider.Message> getMessages(UUID conversationId) {
        List<AiMessage> msgs = messageRepository.findByConversationIdOrderBySeqAsc(conversationId);
        List<LlmProvider.Message> result = new ArrayList<>();
        for (AiMessage m : msgs) {
            result.add(new LlmProvider.Message(m.getRole(), m.getContent()));
        }
        return result;
    }

    @Transactional
    public void appendUserMessage(UUID conversationId, String content) {
        int seq = messageRepository.findByConversationIdOrderBySeqAsc(conversationId).size();
        AiMessage m = new AiMessage();
        m.setId(UUID.randomUUID());
        m.setConversationId(conversationId);
        m.setSeq(seq);
        m.setRole("user");
        m.setContent(content);
        m.setCreatedAt(Instant.now());
        messageRepository.save(m);

        AiConversation conv = conversationRepository.findById(conversationId).orElseThrow();
        conv.setLastMessageAt(Instant.now());
        if (seq == 0 && (conv.getTitle() == null || conv.getTitle().isBlank() || "Chat".equals(conv.getTitle()))) {
            conv.setTitle(deriveTitle(content));
        }
        conversationRepository.save(conv);
    }

    private static String deriveTitle(String firstMessage) {
        String singleLine = firstMessage.replaceAll("\\s+", " ").trim();
        if (singleLine.isEmpty()) return "Chat";
        return singleLine.length() <= 60 ? singleLine : singleLine.substring(0, 57) + "...";
    }

    @Transactional
    public void appendAssistantMessage(UUID conversationId, String content) {
        int seq = messageRepository.findByConversationIdOrderBySeqAsc(conversationId).size();
        AiMessage m = new AiMessage();
        m.setId(UUID.randomUUID());
        m.setConversationId(conversationId);
        m.setSeq(seq);
        m.setRole("assistant");
        m.setContent(content);
        m.setCreatedAt(Instant.now());
        messageRepository.save(m);
    }

    @Transactional(readOnly = true)
    public List<ChatController.ConversationDto> list(UUID projectId, int limit) {
        UUID userId;
        try {
            userId = securityContextHelper.getCurrentUserId();
        } catch (Exception e) {
            return List.of();
        }
        List<AiConversation> convs = projectId != null
                ? conversationRepository.findByUserIdAndProjectIdAndDeletedAtIsNullOrderByLastMessageAtDesc(userId, projectId)
                : conversationRepository.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(userId);
        return convs.stream()
                .limit(limit)
                .map(c -> new ChatController.ConversationDto(c.getId(), c.getTitle(), c.getModule(), c.getLastMessageAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatController.ConversationDetailDto getDetail(UUID id) {
        AiConversation conv = conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id.toString()));
        List<AiMessage> msgs = messageRepository.findByConversationIdOrderBySeqAsc(id);
        List<ChatController.MessageDto> messageDtos = msgs.stream()
                .map(m -> new ChatController.MessageDto(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
        return new ChatController.ConversationDetailDto(conv.getId(), conv.getTitle(), conv.getProjectId(), conv.getModule(), messageDtos);
    }

    @Transactional
    public void softDelete(UUID id) {
        AiConversation conv = conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id.toString()));
        conv.setDeletedAt(Instant.now());
        conversationRepository.save(conv);
    }

    /**
     * Persists the HDS document version scope on the conversation. Called by
     * the chat endpoints when the request carries a non-null hdsVersionIds list
     * — the scope sticks across turns so a follow-up message that omits the
     * field still resolves to HDS retrieval. A null or empty list clears the
     * stored scope.
     */
    @Transactional
    public void saveHdsScope(UUID conversationId, List<String> hdsVersionIds) {
        AiConversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId.toString()));
        conv.setHdsVersionIds(hdsVersionIds == null || hdsVersionIds.isEmpty() ? null : hdsVersionIds);
        conversationRepository.save(conv);
    }
}
