package com.bipros.hds.infrastructure.storage;

import com.bipros.hds.config.HdsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinioHdsStorageService implements HdsStorageService {

    private final HdsProperties props;
    private S3Client s3;
    private S3Presigner presigner;

    @PostConstruct
    public void init() {
        var creds = AwsBasicCredentials.create(props.getStorage().getAccessKey(), props.getStorage().getSecretKey());
        var region = Region.of(props.getStorage().getRegion());
        s3 = S3Client.builder()
            .endpointOverride(URI.create(props.getStorage().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .region(region)
            .forcePathStyle(true)
            .build();
        presigner = S3Presigner.builder()
            .endpointOverride(URI.create(props.getStorage().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .region(region)
            .build();
        // Ensure bucket exists (idempotent - Phase 0 init script also handles this)
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.getStorage().getBucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.getStorage().getBucket()).build());
        } catch (Exception e) {
            log.warn("[HDS Storage] Bucket check failed for '{}': {}", props.getStorage().getBucket(), e.getMessage());
        }
    }

    @Override
    public UploadResult upload(InputStream input, long contentLength, String versionId, String fileName) {
        String key = "hds/" + versionId + "/" + sanitize(fileName);
        String bucket = props.getStorage().getBucket();
        long partSize = (long) props.getStorage().getMultipartPartSizeMb() * 1024L * 1024L;

        try (var shaStream = new ShaInputStream(input)) {
            CreateMultipartUploadResponse mpu = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());

            List<CompletedPart> parts = new ArrayList<>();
            byte[] buf = new byte[(int) partSize];
            int partNumber = 1;
            int read;
            long totalRead = 0;

            while ((read = readFully(shaStream, buf)) > 0) {
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

            return new UploadResult(key, shaStream.hexSha256(), totalRead);
        } catch (IOException e) {
            throw new IllegalStateException("MinIO upload failed", e);
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
    public URL presignGet(String storageKey, Duration ttl) {
        var req = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(GetObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build())
            .build();
        return presigner.presignGetObject(req).url();
    }

    @Override
    public InputStream download(String storageKey) {
        return s3.getObject(
            GetObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build(),
            ResponseTransformer.toInputStream());
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.getStorage().getBucket()).key(storageKey).build());
    }

    private String sanitize(String n) {
        return n == null ? "file.pdf" : n.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
