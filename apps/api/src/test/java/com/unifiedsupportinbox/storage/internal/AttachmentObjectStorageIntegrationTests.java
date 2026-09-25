package com.unifiedsupportinbox.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.storage.AttachmentObjectNotFoundException;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

@Tag("integration")
class AttachmentObjectStorageIntegrationTests {

    private static final GenericContainer<?> MINIO = TestInfrastructure.minio();
    private static final String BUCKET = "usi-attachments-test";

    private static ConfigurableApplicationContext context;
    private static AttachmentObjectStorage storage;

    @BeforeAll
    static void startApplication() {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--usi.object-storage.endpoint=" + endpoint,
                        "--usi.object-storage.region=us-east-1",
                        "--usi.object-storage.attachments-bucket=" + BUCKET,
                        "--usi.object-storage.access-key=usi-test-access",
                        "--usi.object-storage.secret-key=usi-test-secret-key",
                        "--usi.bootstrap-admin.enabled=false");
        context.getBean(S3Client.class).createBucket(builder -> builder.bucket(BUCKET));
        storage = context.getBean(AttachmentObjectStorage.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        MINIO.stop();
    }

    @Test
    void streamsAttachmentToMinioReadsItAndDeletesIt() throws Exception {
        byte[] payload = "załącznik 🧪".getBytes(StandardCharsets.UTF_8);
        String key = AttachmentStorageKey.generate();

        storage.put(key, new ByteArrayInputStream(payload), payload.length, "text/plain; charset=utf-8");

        assertThat(storage.exists(key)).isTrue();
        try (var content = storage.open(key)) {
            assertThat(content.readAllBytes()).isEqualTo(payload);
        }

        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void missingObjectHasStableDomainError() {
        String key = AttachmentStorageKey.generate();

        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.open(key))
                .isInstanceOf(AttachmentObjectNotFoundException.class)
                .hasMessageContaining(key);
    }

    @Test
    void generatedStorageKeyNeverContainsUserFilenameOrTraversal() {
        String key = AttachmentStorageKey.generate();

        assertThat(key).startsWith("attachments/");
        assertThat(key).doesNotContain("..", "\\");
        assertThat(key).doesNotContain("invoice.pdf");
    }
}
