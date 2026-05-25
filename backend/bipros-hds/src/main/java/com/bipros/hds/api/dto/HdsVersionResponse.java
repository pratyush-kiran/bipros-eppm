package com.bipros.hds.api.dto;

import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HdsVersionResponse(UUID id, UUID hdsDocumentId, String versionLabel,
                                 Integer revisionYear, LocalDate effectiveDate,
                                 String fileName, Long fileSizeBytes, Integer pageCount,
                                 HdsVersionStatus status, Integer indexingProgressPct,
                                 Integer chunkCount, Instant uploadedAt, Instant indexedAt) {
    public static HdsVersionResponse from(HdsVersion v) {
        return new HdsVersionResponse(v.getId(), v.getHdsDocumentId(), v.getVersionLabel(),
            v.getRevisionYear(), v.getEffectiveDate(), v.getFileName(), v.getFileSizeBytes(),
            v.getPageCount(), v.getStatus(), v.getIndexingProgressPct(),
            v.getChunkCount(), v.getUploadedAt(), v.getIndexedAt());
    }
}
