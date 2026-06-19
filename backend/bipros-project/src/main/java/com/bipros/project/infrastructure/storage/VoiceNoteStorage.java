package com.bipros.project.infrastructure.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Binary store for DPR voice notes. The single implementation streams through to MinIO, but the
 * interface keeps a future swap (e.g. pre-signed direct upload/download) localized. Uploads and
 * downloads are heap-flat: uploads push ~5 MB parts, downloads request only the byte window asked
 * for via {@link #openRange}.
 */
public interface VoiceNoteStorage {

    /** Streams {@code in} into the store under a generated key. Returns the persisted metadata. */
    StoredObject upload(InputStream in, long len, String contentType, UUID dprId, String originalFilename);

    /** Total object size in bytes — used to set Content-Length / Content-Range when streaming. */
    long contentLength(String key);

    /** Opens an inclusive byte range {@code [start, end]} of the object (S3 GetObject range). */
    InputStream openRange(String key, long start, long end);

    /** Opens the full object. */
    InputStream openFull(String key);

    /** Best-effort delete — missing objects are swallowed so a DB rollback isn't blocked. */
    void delete(String key);

    record StoredObject(String storageKey, String fileName, String mimeType, long fileSize) {}
}
