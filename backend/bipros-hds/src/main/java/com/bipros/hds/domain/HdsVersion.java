package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hds_version", schema = "hds",
       uniqueConstraints = {
         @UniqueConstraint(name = "uk_hds_version_doc_label", columnNames = {"hds_document_id", "version_label"})
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsVersion extends BaseEntity {

    @Column(name = "hds_document_id", nullable = false)
    private UUID hdsDocumentId;

    @Column(name = "version_label", nullable = false, length = 64)
    private String versionLabel;

    @Column(name = "revision_year")
    private Integer revisionYear;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private HdsVersionStatus status = HdsVersionStatus.PENDING;

    @Column(name = "indexing_progress_pct", nullable = false)
    @Builder.Default
    private Integer indexingProgressPct = 0;

    @Column(name = "indexing_error", columnDefinition = "text")
    private String indexingError;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;
}
