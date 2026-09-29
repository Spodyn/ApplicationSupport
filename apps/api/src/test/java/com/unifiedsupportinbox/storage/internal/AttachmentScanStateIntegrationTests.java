package com.unifiedsupportinbox.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class AttachmentScanStateIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static AttachmentMetadataCatalog catalog;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false");
        catalog = context.getBean(AttachmentMetadataCatalog.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM attachments");
    }

    @Test
    void exactlyOneConcurrentWorkerClaimsPendingAttachment() throws Exception {
        AttachmentMetadata pending = createPending();
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Optional<AttachmentMetadata>>> attempts = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return catalog.claimForScan(pending.id());
                }));
            }
            ready.await();
            start.countDown();

            List<AttachmentMetadata> winners = new ArrayList<>();
            for (Future<Optional<AttachmentMetadata>> attempt : attempts) {
                attempt.get().ifPresent(winners::add);
            }
            assertThat(winners).hasSize(1);
            assertThat(winners.getFirst().scanStatus()).isEqualTo(AttachmentScanStatus.SCANNING);
        }

        AttachmentMetadata clean = catalog.completeScan(
                pending.id(), "safe.txt", "text/plain", AttachmentScanStatus.CLEAN, null);
        assertThat(clean.scanStatus()).isEqualTo(AttachmentScanStatus.CLEAN);
        assertThat(catalog.claimForScan(pending.id())).isEmpty();
    }

    @Test
    void scannerErrorCanBeExplicitlyReclaimedButInfectedCannot() {
        AttachmentMetadata pending = createPending();
        assertThat(catalog.claimForScan(pending.id())).isPresent();
        AttachmentMetadata error = catalog.completeScan(
                pending.id(), "safe.txt", "text/plain", AttachmentScanStatus.ERROR, "SCANNER_TIMEOUT");
        assertThat(error.scanStatus()).isEqualTo(AttachmentScanStatus.ERROR);

        assertThat(catalog.claimForScan(pending.id())).isPresent();
        AttachmentMetadata infected = catalog.completeScan(
                pending.id(), "safe.txt", "text/plain", AttachmentScanStatus.INFECTED, null);
        assertThat(infected.scanStatus()).isEqualTo(AttachmentScanStatus.INFECTED);
        assertThat(catalog.claimForScan(pending.id())).isEmpty();
    }

    private static AttachmentMetadata createPending() {
        return catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                null,
                AttachmentStorageKey.generate(),
                "safe.txt",
                "text/plain",
                null,
                5,
                "a".repeat(64),
                AttachmentScanStatus.PENDING,
                null,
                null));
    }
}
