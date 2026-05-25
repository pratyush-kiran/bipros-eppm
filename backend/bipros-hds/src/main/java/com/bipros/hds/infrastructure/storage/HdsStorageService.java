package com.bipros.hds.infrastructure.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

public interface HdsStorageService {
    /** Uploads via streaming multipart. Returns the storage key. */
    UploadResult upload(InputStream input, long contentLength, String versionId, String fileName);

    /** Presigned GET URL for the version's PDF, valid for the given duration. */
    URL presignGet(String storageKey, Duration ttl);

    InputStream download(String storageKey);

    void delete(String storageKey);

    record UploadResult(String storageKey, String sha256, long size) {}
}
