package com.bipros.hds.application.library;

import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.application.library.dto.UpdateHdsDocumentInput;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.HdsIngestionJob;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsIngestionStage;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsChunkRepository;
import com.bipros.hds.domain.repo.HdsDocumentRepository;
import com.bipros.hds.domain.repo.HdsIngestionJobRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class HdsLibraryService {

    private final HdsDocumentRepository docRepo;
    private final HdsVersionRepository versionRepo;
    private final HdsIngestionJobRepository jobRepo;
    private final HdsChunkRepository chunkRepo;
    private final HdsStorageService storage;

    public HdsDocument createDocument(CreateHdsDocumentInput in) {
        if (docRepo.existsByShortCode(in.shortCode())) {
            throw new IllegalArgumentException("Short code already in use: " + in.shortCode());
        }
        var doc = HdsDocument.builder()
            .title(in.title())
            .shortCode(in.shortCode())
            .discipline(in.discipline())
            .issuingAuthority(in.issuingAuthority())
            .country(in.country())
            .description(in.description())
            .build();
        return docRepo.save(doc);
    }

    public HdsDocument updateDocument(UUID id, UpdateHdsDocumentInput in) {
        var doc = docRepo.findById(id).orElseThrow();
        if (in.title() != null) doc.setTitle(in.title());
        if (in.discipline() != null) doc.setDiscipline(in.discipline());
        if (in.issuingAuthority() != null) doc.setIssuingAuthority(in.issuingAuthority());
        if (in.country() != null) doc.setCountry(in.country());
        if (in.description() != null) doc.setDescription(in.description());
        return docRepo.save(doc);
    }

    public List<HdsDocument> listDocuments() {
        return docRepo.findAll();
    }

    public void deleteDocument(UUID id) {
        var versions = versionRepo.findByHdsDocumentIdOrderByRevisionYearDesc(id);
        for (var v : versions) deleteVersion(v.getId());
        docRepo.deleteById(id);
    }

    @Transactional(noRollbackFor = DuplicateUploadException.class)
    public HdsVersion uploadVersion(UUID documentId, String versionLabel, Integer revisionYear,
                                    InputStream pdfStream, long contentLength, String fileName,
                                    UUID uploadedBy) {
        docRepo.findById(documentId).orElseThrow();

        // Save the row first so JPA generates the id; we can't pre-assign the UUID without
        // tripping Spring Data's "detached entity" / @Version optimistic lock check.
        var version = HdsVersion.builder()
            .hdsDocumentId(documentId)
            .versionLabel(versionLabel)
            .revisionYear(revisionYear)
            .fileName(fileName)
            .storageKey("__pending__")    // placeholder; set after upload
            .fileSha256(null)             // tolerable: SHA uniqueness lookup runs after upload
            .status(HdsVersionStatus.PENDING)
            .uploadedBy(uploadedBy)
            .uploadedAt(Instant.now())
            .build();
        version = versionRepo.save(version);

        // Now upload using the saved id so the MinIO key matches the row.
        HdsStorageService.UploadResult uploadResult;
        try {
            uploadResult = storage.upload(pdfStream, contentLength, version.getId().toString(), fileName);
        } catch (RuntimeException uploadFail) {
            versionRepo.delete(version);   // undo placeholder
            throw uploadFail;
        }

        // Idempotency by SHA-256: if another version already has this hash, throw away ours.
        Optional<HdsVersion> existing = versionRepo.findByFileSha256(uploadResult.sha256());
        if (existing.isPresent() && !existing.get().getId().equals(version.getId())) {
            storage.delete(uploadResult.storageKey());
            versionRepo.delete(version);
            throw new DuplicateUploadException(existing.get());
        }

        version.setFileSizeBytes(uploadResult.size());
        version.setFileSha256(uploadResult.sha256());
        version.setStorageKey(uploadResult.storageKey());
        version = versionRepo.save(version);

        var job = new HdsIngestionJob();
        job.setHdsVersionId(version.getId());
        job.setStage(HdsIngestionStage.PARSING);
        job.setProgressPct(0);
        job.setAttemptCount(0);
        job.setStartedAt(Instant.now());
        job.setLastHeartbeatAt(Instant.now());
        jobRepo.save(job);

        return version;
    }

    public HdsVersion getVersion(UUID id) {
        return versionRepo.findById(id).orElseThrow();
    }

    public List<HdsVersion> listVersions(UUID documentId) {
        return versionRepo.findByHdsDocumentIdOrderByRevisionYearDesc(documentId);
    }

    public List<HdsVersion> listIndexedVersions() {
        return versionRepo.findByStatus(HdsVersionStatus.INDEXED);
    }

    public void retryVersion(UUID versionId) {
        var version = versionRepo.findById(versionId).orElseThrow();
        var jobOpt = jobRepo.findByHdsVersionId(versionId);
        var job = jobOpt.orElseGet(() -> {
            var j = new HdsIngestionJob();
            j.setHdsVersionId(versionId);
            return j;
        });
        // Roll back to the stage before the failure
        job.setStage(switch (version.getStatus()) {
            case PARSING, PENDING -> HdsIngestionStage.PARSING;
            case CHUNKING -> HdsIngestionStage.CHUNKING;
            case EMBEDDING -> HdsIngestionStage.EMBEDDING;
            case FAILED -> HdsIngestionStage.PARSING; // safe default
            default -> HdsIngestionStage.INDEXING;
        });
        job.setErrorMessage(null);
        job.setLastHeartbeatAt(null);
        jobRepo.save(job);
        version.setStatus(HdsVersionStatus.PENDING);
        version.setIndexingError(null);
        version.setIndexingProgressPct(0);
        versionRepo.save(version);
    }

    public void deleteVersion(UUID versionId) {
        var version = versionRepo.findById(versionId).orElseThrow();
        chunkRepo.deleteByHdsVersionId(versionId);
        try { storage.delete(version.getStorageKey()); } catch (Exception ignored) {}
        jobRepo.findByHdsVersionId(versionId).ifPresent(jobRepo::delete);
        versionRepo.deleteById(versionId);
    }

    public static class DuplicateUploadException extends RuntimeException {
        private final HdsVersion existing;
        public DuplicateUploadException(HdsVersion existing) {
            super("Duplicate SHA-256: existing version " + existing.getId());
            this.existing = existing;
        }
        public HdsVersion getExisting() { return existing; }
    }
}
