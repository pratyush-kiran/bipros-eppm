package com.bipros.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "ai", name = "ai_conversations")
@Getter
@Setter
public class AiConversation {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "module")
    private String module;

    @Column(name = "title")
    private String title;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * HDS document version UUIDs the user selected as the scope for this
     * conversation. Persisted as JSONB so a later turn that omits the field
     * on the request can still resolve the scope from the conversation's
     * stored selection. {@code null} or empty list means the conversation is
     * not scoped to any HDS standards.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hds_version_ids", columnDefinition = "jsonb")
    private List<String> hdsVersionIds;
}
