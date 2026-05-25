package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsChunkType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "hds_chunk", schema = "hds",
       indexes = {
         @Index(name = "idx_hds_chunk_version_idx", columnList = "hds_version_id, chunk_index"),
         @Index(name = "idx_hds_chunk_section_path", columnList = "section_path")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsChunk extends BaseEntity {

    @Column(name = "hds_version_id", nullable = false)
    private UUID hdsVersionId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "page_start", nullable = false)
    private Integer pageStart;

    @Column(name = "page_end", nullable = false)
    private Integer pageEnd;

    @Column(name = "section_path", nullable = false, columnDefinition = "text")
    private String sectionPath;

    @Column(name = "section_number", length = 32)
    private String sectionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false, length = 16)
    private HdsChunkType chunkType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_tokens")
    private Integer contentTokens;

    /**
     * Note: embedding is written via raw SQL (HybridSearchRepository / native insert),
     * not by JPA, because pgvector type binding is handled by the pgvector-java driver.
     * Keep this field for read-time reflection if needed; mark non-insertable/updatable.
     */
    @Column(name = "embedding", insertable = false, updatable = false, columnDefinition = "vector(1536)")
    private String embeddingRaw;
}
