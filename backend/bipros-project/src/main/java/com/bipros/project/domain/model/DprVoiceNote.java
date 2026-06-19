package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Audio "voice note" attached to a {@link DailyProgressReport}. Mirrors {@link DprAttachment} but
 * the binary lives in MinIO (durable, multi-instance-safe) rather than on local disk. Lifecycle is
 * independent of the resource child rows: voice notes are uploaded after the DPR is saved (the API
 * client posts the DPR row first, then sends the multipart voice-note request against the returned
 * id), and they are deleted explicitly. {@code dprId} is a soft FK; the storage service deletes the
 * binary when the row is removed.
 *
 * <p>This is distinct from the transcribe-and-discard "Voice fill" assistant — voice notes are
 * persisted and never transcribed; they are an attachment, like a photo.
 */
@Entity
@Table(
    name = "dpr_voice_notes",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_voice_notes_dpr", columnList = "dpr_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprVoiceNote extends BaseEntity {

    @Column(name = "dpr_id", nullable = false)
    private UUID dprId;

    /** Denormalised so we can query voice notes without joining DPR. */
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** S3 object key in the MinIO bucket, of the form {@code voice-notes/{dprId}/{uuid}.{ext}}. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** Best-effort recording length in seconds reported by the client; null when unknown. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "caption", length = 500)
    private String caption;
}
