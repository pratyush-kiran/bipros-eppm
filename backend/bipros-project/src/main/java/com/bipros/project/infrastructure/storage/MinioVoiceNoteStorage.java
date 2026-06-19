package com.bipros.project.infrastructure.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * MinIO-backed {@link VoiceNoteStorage}. Mirrors {@code MinioHdsStorageService}: an {@code S3Client}
 * with {@code .endpointOverride(...)} + {@code .forcePathStyle(true)}, idempotent bucket-create in
 * {@link PostConstruct}, and a heap-flat multipart upload loop holding one ~5 MB part at a time.
 *
 * <p>Voice-note specifics vs. the HDS recipe: (a) the upload sets {@code .contentType(...)} on the
 * multipart create so the MIME round-trips for {@code <audio>} playback; (b) {@link #openRange}
 * pushes the byte window to the S3 {@code GetObject} {@code Range} header so MinIO streams only the
 * requested bytes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MinioVoiceNoteStorage implements VoiceNoteStorage {

    private final VoiceNoteStorageProperties props;
    private S3Client s3;

    @PostConstruct
    public void init() {
        var creds = AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey());
        s3 = S3Client.builder()
            .endpointOverride(URI.create(props.getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .region(Region.of(props.getRegion()))
            .forcePathStyle(true)
            .build();
        // Ensure bucket exists (idempotent). Swallow failures — never crash boot if MinIO is down.
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.getBucket()).build());
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(props.getBucket()).build());
            } catch (Exception ce) {
                log.warn("[DPR voice notes] bucket create failed for '{}': {}", props.getBucket(), ce.getMessage());
            }
        } catch (Exception e) {
            log.warn("[DPR voice notes] bucket check failed for '{}': {}", props.getBucket(), e.getMessage());
        }
    }

    @Override
    public StoredObject upload(InputStream in, long len, String contentType, UUID dprId, String originalFilename) {
        String fileName = sanitize(originalFilename, contentType);
        String key = "voice-notes/" + dprId + "/" + UUID.randomUUID() + extensionFor(originalFilename, contentType);
        String bucket = props.getBucket();
        long partSize = (long) props.getMultipartPartSizeMb() * 1024L * 1024L;

        try (InputStream input = in) {
            CreateMultipartUploadResponse mpu = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .contentType(contentType)
                    .build());

            List<CompletedPart> parts = new ArrayList<>();
            byte[] buf = new byte[(int) partSize];
            int partNumber = 1;
            int read;
            long totalRead = 0;

            while ((read = readFully(input, buf)) > 0) {
                byte[] payload = read == buf.length ? buf : Arrays.copyOf(buf, read);
                var partResp = s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket).key(key)
                        .uploadId(mpu.uploadId())
                        .partNumber(partNumber)
                        .contentLength((long) read)
                        .build(),
                    RequestBody.fromInputStream(new ByteArrayInputStream(payload), read));
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResp.eTag()).build());
                totalRead += read;
                partNumber++;
            }

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key)
                .uploadId(mpu.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());

            return new StoredObject(key, fileName, contentType, totalRead);
        } catch (IOException e) {
            throw new IllegalStateException("MinIO voice-note upload failed", e);
        }
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    @Override
    public long contentLength(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(key).build())
            .contentLength();
    }

    @Override
    public InputStream openRange(String key, long start, long end) {
        return s3.getObject(
            GetObjectRequest.builder()
                .bucket(props.getBucket()).key(key)
                .range("bytes=" + start + "-" + end)
                .build(),
            ResponseTransformer.toInputStream());
    }

    @Override
    public InputStream openFull(String key) {
        return s3.getObject(
            GetObjectRequest.builder().bucket(props.getBucket()).key(key).build(),
            ResponseTransformer.toInputStream());
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(props.getBucket()).key(key).build());
        } catch (Exception e) {
            // Missing object / transient error: log, don't fail the surrounding DB transaction.
            log.warn("[DPR voice notes] failed to delete '{}': {}", key, e.getMessage());
        }
    }

    /** Display file name kept on the row — falls back to a generic name when the client sent none. */
    private static String sanitize(String originalFilename, String contentType) {
        String original = StringUtils.cleanPath(
            originalFilename == null || originalFilename.isBlank()
                ? "voice-note" + extensionFor(null, contentType)
                : originalFilename);
        String name = original.replaceAll("[\\\\/\\x00]", "_");
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    /** Derive a file extension from the original filename when present, else from the MIME type. */
    private static String extensionFor(String filename, String mimeType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{1,5}")) {
                    return ext;
                }
            }
        }
        String mime = mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase();
        return switch (mime) {
            case "audio/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/mpeg" -> ".mp3";
            case "audio/aac" -> ".aac";
            case "audio/wav", "audio/x-wav" -> ".wav";
            default -> ".bin";
        };
    }
}
