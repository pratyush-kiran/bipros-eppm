package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.DprVoiceNoteResponse;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprVoiceNote;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprVoiceNoteRepository;
import com.bipros.project.infrastructure.storage.VoiceNoteStorage;
import com.bipros.project.infrastructure.storage.VoiceNoteStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages audio voice notes hanging off a DPR row. Lifecycle mirrors {@link DprAttachmentService}
 * (photos) — notes are uploaded after the DPR is saved (the client posts the row first, then sends
 * a multipart voice-note request). Binaries live in MinIO via {@link VoiceNoteStorage}; metadata
 * lives in {@code project.dpr_voice_notes}.
 *
 * <p>If a note's DB write fails we attempt best-effort cleanup of the just-written MinIO objects so
 * the bucket doesn't accumulate orphans. Validation (MIME whitelist, size cap, non-empty) lives
 * here so the storage layer stays a thin byte mover.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DprVoiceNoteService {

  private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
      "audio/webm",
      "audio/ogg",
      "audio/mp4",
      "audio/mpeg",
      "audio/aac",
      "audio/wav",
      "audio/x-wav",
      "audio/x-m4a"
  );

  private final DailyProgressReportRepository dprRepository;
  private final DprVoiceNoteRepository voiceNoteRepository;
  private final VoiceNoteStorage storage;
  private final VoiceNoteStorageProperties properties;

  public List<DprVoiceNoteResponse> addAll(
      UUID projectId, UUID dprId, List<MultipartFile> files, List<String> captions, List<Integer> durations) {
    DailyProgressReport dpr = requireDpr(projectId, dprId);
    if (files == null || files.isEmpty()) {
      return List.of();
    }

    List<DprVoiceNote> created = new ArrayList<>(files.size());
    List<String> writtenKeys = new ArrayList<>(files.size());
    try {
      for (int i = 0; i < files.size(); i++) {
        MultipartFile file = files.get(i);
        validate(file);
        String caption = captions == null || i >= captions.size() ? null : captions.get(i);
        Integer duration = durations == null || i >= durations.size() ? null : durations.get(i);
        VoiceNoteStorage.StoredObject stored;
        try {
          stored = storage.upload(
              file.getInputStream(), file.getSize(), file.getContentType(), dprId, file.getOriginalFilename());
        } catch (IOException e) {
          throw new IllegalStateException("Failed to read uploaded voice note", e);
        }
        writtenKeys.add(stored.storageKey());
        DprVoiceNote entity = DprVoiceNote.builder()
            .dprId(dprId)
            .projectId(dpr.getProjectId())
            .fileName(stored.fileName())
            .mimeType(stored.mimeType())
            .fileSize(stored.fileSize())
            .storageKey(stored.storageKey())
            .durationSeconds(duration)
            .caption(caption)
            .build();
        created.add(entity);
      }
      List<DprVoiceNote> saved = voiceNoteRepository.saveAll(created);
      return saved.stream().map(DprVoiceNoteResponse::from).toList();
    } catch (RuntimeException ex) {
      // Best-effort cleanup: any objects we wrote in this call get removed so we don't leak.
      for (String key : writtenKeys) {
        storage.delete(key);
      }
      throw ex;
    }
  }

  @Transactional(readOnly = true)
  public List<DprVoiceNoteResponse> list(UUID projectId, UUID dprId) {
    requireDpr(projectId, dprId);
    return voiceNoteRepository.findByDprIdOrderByCreatedAtAsc(dprId).stream()
        .map(DprVoiceNoteResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public LoadedVoiceNote loadMeta(UUID projectId, UUID dprId, UUID voiceNoteId) {
    DprVoiceNote note = requireNote(projectId, dprId, voiceNoteId);
    return new LoadedVoiceNote(note.getStorageKey(), note.getMimeType(), note.getFileName(), note.getFileSize());
  }

  public void delete(UUID projectId, UUID dprId, UUID voiceNoteId) {
    DprVoiceNote note = requireNote(projectId, dprId, voiceNoteId);
    String key = note.getStorageKey();
    voiceNoteRepository.delete(note);
    storage.delete(key);
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessRuleException("DPR_VOICE_NOTE_EMPTY", "Uploaded voice note is empty");
    }
    if (file.getSize() > properties.getMaxFileSize()) {
      throw new BusinessRuleException(
          "DPR_VOICE_NOTE_TOO_LARGE",
          "Voice note exceeds max allowed size of " + properties.getMaxFileSize() + " bytes");
    }
    // Browsers may report a parameterised type, e.g. Chrome's MediaRecorder emits
    // "audio/webm;codecs=opus" — match on the base type only.
    String rawMime = file.getContentType() == null ? "" : file.getContentType();
    String mimeType = rawMime.split(";", 2)[0].trim().toLowerCase();
    if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
      throw new BusinessRuleException(
          "DPR_VOICE_NOTE_UNSUPPORTED_TYPE",
          "Unsupported audio MIME type: " + rawMime);
    }
  }

  private DailyProgressReport requireDpr(UUID projectId, UUID dprId) {
    DailyProgressReport dpr = dprRepository.findById(dprId)
        .orElseThrow(() -> new ResourceNotFoundException("DailyProgressReport", dprId));
    if (!dpr.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("DailyProgressReport", dprId);
    }
    return dpr;
  }

  private DprVoiceNote requireNote(UUID projectId, UUID dprId, UUID voiceNoteId) {
    DprVoiceNote note = voiceNoteRepository.findById(voiceNoteId)
        .orElseThrow(() -> new ResourceNotFoundException("DprVoiceNote", voiceNoteId));
    if (!note.getDprId().equals(dprId) || !note.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("DprVoiceNote", voiceNoteId);
    }
    return note;
  }

  public record LoadedVoiceNote(String storageKey, String mimeType, String fileName, long fileSize) {}
}
