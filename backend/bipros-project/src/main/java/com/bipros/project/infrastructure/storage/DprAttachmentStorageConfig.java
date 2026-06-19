package com.bipros.project.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers DPR storage configuration-properties beans so Spring binds {@code bipros.dpr.storage.*}
 * (photos, local disk) and {@code bipros.dpr.voice-notes.*} (audio, MinIO) on startup.
 */
@Configuration
@EnableConfigurationProperties({DprAttachmentStorageProperties.class, VoiceNoteStorageProperties.class})
public class DprAttachmentStorageConfig {
}
