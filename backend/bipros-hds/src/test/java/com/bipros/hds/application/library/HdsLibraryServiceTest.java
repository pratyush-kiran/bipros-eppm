package com.bipros.hds.application.library;

import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsDiscipline;
import com.bipros.hds.domain.repo.*;
import com.bipros.hds.infrastructure.storage.HdsStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HdsLibraryServiceTest {

    @Test
    void createRejectsDuplicateShortCode() {
        var docRepo = mock(HdsDocumentRepository.class);
        when(docRepo.existsByShortCode("HDS-V3")).thenReturn(true);
        var svc = new HdsLibraryService(docRepo, mock(HdsVersionRepository.class),
            mock(HdsIngestionJobRepository.class), mock(HdsChunkRepository.class),
            mock(HdsStorageService.class));
        assertThatThrownBy(() -> svc.createDocument(new CreateHdsDocumentInput(
            "x","HDS-V3", HdsDiscipline.HIGHWAY, null, null, null)))
            .hasMessageContaining("Short code already in use");
    }

    @Test
    void uploadIsIdempotentBySha() {
        var docRepo = mock(HdsDocumentRepository.class);
        var verRepo = mock(HdsVersionRepository.class);
        var jobRepo = mock(HdsIngestionJobRepository.class);
        var chunkRepo = mock(HdsChunkRepository.class);
        var storage = mock(HdsStorageService.class);

        UUID docId = UUID.randomUUID();
        when(docRepo.findById(docId)).thenReturn(Optional.of(new HdsDocument()));
        when(storage.upload(any(), anyLong(), anyString(), anyString()))
            .thenReturn(new HdsStorageService.UploadResult("hds/x/y.pdf", "shadup".repeat(11) + "ab", 100));
        var existing = new HdsVersion();
        when(verRepo.findByFileSha256(anyString())).thenReturn(Optional.of(existing));

        var svc = new HdsLibraryService(docRepo, verRepo, jobRepo, chunkRepo, storage);
        assertThatThrownBy(() -> svc.uploadVersion(docId, "Rev 1", 2024,
            new ByteArrayInputStream(new byte[]{1}), 1, "f.pdf", UUID.randomUUID()))
            .isInstanceOf(HdsLibraryService.DuplicateUploadException.class);
        verify(storage).delete("hds/x/y.pdf");
    }
}
