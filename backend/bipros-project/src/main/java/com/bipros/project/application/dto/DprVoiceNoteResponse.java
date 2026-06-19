package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprVoiceNote;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection of {@link DprVoiceNote} returned to the frontend. The audio binary itself
 * is streamed from {@code GET /v1/projects/{projectId}/dpr/{dprId}/voice-notes/{voiceNoteId}/stream}
 * (Range-aware); the URL is built client-side from {@code id} + the parent ids. The S3 storage key
 * is intentionally NOT exposed.
 */
public record DprVoiceNoteResponse(
    UUID id,
    UUID dprId,
    String fileName,
    String mimeType,
    Long fileSize,
    Integer durationSeconds,
    String caption,
    Instant createdAt
) {
  public static DprVoiceNoteResponse from(DprVoiceNote v) {
    return new DprVoiceNoteResponse(
        v.getId(),
        v.getDprId(),
        v.getFileName(),
        v.getMimeType(),
        v.getFileSize(),
        v.getDurationSeconds(),
        v.getCaption(),
        v.getCreatedAt()
    );
  }
}
