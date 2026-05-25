package com.bipros.hds.infrastructure.storage;

import com.bipros.hds.config.HdsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "HDS_RUN_MINIO_IT", matches = "true")
class MinioHdsStorageServiceIT {

    @Test
    void roundTripsBytes() throws Exception {
        HdsProperties props = new HdsProperties();
        props.getStorage().setBucket("hds");
        props.getStorage().setEndpoint("http://localhost:9000");
        props.getStorage().setAccessKey("minio");
        props.getStorage().setSecretKey("minio123");
        props.getStorage().setRegion("us-east-1");
        props.getStorage().setMultipartPartSizeMb(5);

        MinioHdsStorageService svc = new MinioHdsStorageService(props);
        svc.init();

        byte[] payload = "hello hds".getBytes();
        var result = svc.upload(new ByteArrayInputStream(payload), payload.length, "test-version-1", "x.pdf");
        assertThat(result.storageKey()).contains("test-version-1");
        assertThat(result.sha256()).hasSize(64);

        byte[] back = svc.download(result.storageKey()).readAllBytes();
        assertThat(back).isEqualTo(payload);

        var url = svc.presignGet(result.storageKey(), Duration.ofMinutes(5));
        assertThat(url.toString()).contains("X-Amz-Signature");

        svc.delete(result.storageKey());
    }
}
