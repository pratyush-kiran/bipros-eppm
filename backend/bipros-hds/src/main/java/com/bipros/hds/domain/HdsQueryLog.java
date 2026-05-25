package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "hds_query_log", schema = "hds",
       indexes = @Index(name = "idx_hds_query_log_user_created", columnList = "user_id, created_at DESC"))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsQueryLog extends BaseEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "query_text", columnDefinition = "text", nullable = false)
    private String queryText;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "selected_version_ids", columnDefinition = "uuid[]")
    private UUID[] selectedVersionIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "retrieved_chunk_ids", columnDefinition = "uuid[]")
    private UUID[] retrievedChunkIds;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb")
    private List<Map<String, Object>> citations;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage", columnDefinition = "jsonb")
    private Map<String, Object> tokenUsage;

    @Column(name = "verifier_passed")
    private Boolean verifierPassed;

    @Column(name = "rounds")
    private Integer rounds;
}
