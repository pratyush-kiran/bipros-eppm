package com.bipros.project.infrastructure.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO/S3 connection + sizing knobs for DPR voice-note binaries. Unlike the local-disk photo
 * storage ({@code bipros.dpr.storage}), voice notes stream through to MinIO so the same object is
 * durable and reachable from every app instance.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "bipros.dpr.voice-notes")
public class VoiceNoteStorageProperties {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "minio";
    private String secretKey = "minio123";
    private String region = "us-east-1";
    private String bucket = "dpr-voice-notes";

    /** Multipart part size in MiB. S3/MinIO reject non-final parts below 5 MiB — don't go lower. */
    private int multipartPartSizeMb = 5;

    /** Maximum file size allowed per upload in bytes. Defaults to 25 MB. */
    private long maxFileSize = 25L * 1024 * 1024;
}
