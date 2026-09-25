package com.unifiedsupportinbox.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class AttachmentMetadataIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final String SHA256 = "a".repeat(64);

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AttachmentMetadataCatalog catalog;

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
        jdbc = context.getBean(JdbcTemplate.class);
        catalog = context.getBean(AttachmentMetadataCatalog.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearAttachmentState() {
        jdbc.update("DELETE FROM attachments");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void persistsBrowserSafeMetadataAndAssociatesItWithMessage() {
        UUID messageId = fixtureMessage();
        String storageKey = AttachmentStorageKey.generate();
        AttachmentMetadata created = catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                messageId, storageKey, "invoice.pdf", "application/pdf", "application/pdf", 1234,
                SHA256, AttachmentScanStatus.CLEAN, null, "F-provider-123"));
        assertThat(created.id()).isNotNull();
        assertThat(created.messageId()).isEqualTo(messageId);
        assertThat(created.storageKey()).isEqualTo(storageKey);
        assertThat(created.originalFilename()).isEqualTo("invoice.pdf");
        assertThat(created.sizeBytes()).isEqualTo(1234);
        assertThat(created.sha256()).isEqualTo(SHA256);
        assertThat(created.scanStatus()).isEqualTo(AttachmentScanStatus.CLEAN);
        assertThat(created.providerFileId()).isEqualTo("F-provider-123");
        assertThat(created.createdAt()).isNotNull();
        assertThat(catalog.findById(created.id())).contains(created);
        assertThat(catalog.findByMessageId(messageId)).containsExactly(created);
    }

    @Test
    void pendingAttachmentCanBeCreatedBeforeMessageAndAssociatedLater() {
        AttachmentMetadata pending = catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                null, AttachmentStorageKey.generate(), "photo.png", "image/png", null, 42,
                "b".repeat(64), AttachmentScanStatus.PENDING, null, null));
        UUID messageId = fixtureMessage();
        AttachmentMetadata associated = catalog.associateWithMessage(pending.id(), messageId);
        assertThat(associated.messageId()).isEqualTo(messageId);
        assertThat(catalog.findByMessageId(messageId)).containsExactly(associated);
    }

    @Test
    void messageForeignKeyRejectsAssociationToUnknownMessage() {
        AttachmentMetadata pending = catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                null, AttachmentStorageKey.generate(), "note.txt", "text/plain", "text/plain", 5,
                "c".repeat(64), AttachmentScanStatus.CLEAN, null, null));
        assertThatThrownBy(() -> catalog.associateWithMessage(pending.id(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void metadataValidationRejectsUnsafeStorageKeysAndInvalidScanErrorState() {
        assertThatThrownBy(() -> catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                        null, "../../etc/passwd", "file.txt", "text/plain", null, 1,
                        SHA256, AttachmentScanStatus.PENDING, null, null)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("storageKey");
        assertThatThrownBy(() -> catalog.create(new AttachmentMetadataCatalog.CreateAttachment(
                        null, AttachmentStorageKey.generate(), "file.txt", "text/plain", null, 1,
                        SHA256, AttachmentScanStatus.ERROR, null, null)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("scanError");
    }

    @Test
    void requiredAttachmentIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'attachments'",
                String.class);
        assertThat(indexes).contains(
                "attachments_pkey", "attachments_storage_key_key",
                "idx_attachments_message_created", "idx_attachments_provider_file");
    }

    private static UUID fixtureMessage() {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id", UUID.class,
                "Attachment customer " + suffix, "attachment-customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, "Attachment Slack " + suffix, "attachment-workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id,
                                      ignored, grouping_strategy, active)
                VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integrationId, "attachment-channel-" + suffix,
                "support-" + suffix, customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider,
                                   external_conversation_id, external_thread_key, status)
                VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW') RETURNING id
                """, UUID.class, customerId, integrationId, channelId,
                "attachment-conversation-" + suffix, "attachment-root-" + suffix);
        return jdbc.queryForObject("""
                INSERT INTO messages (case_id, external_message_id, external_thread_key, kind,
                                      author_external_id, author_name, body, body_format,
                                      inbound, provider_created_at, correlation_id)
                VALUES (?, ?, ?, 'CUSTOMER', 'U-attachment', 'Customer', 'message',
                        'PLAIN_TEXT', TRUE, CURRENT_TIMESTAMP, ?) RETURNING id
                """, UUID.class, caseId, "attachment-message-" + suffix,
                "attachment-root-" + suffix, "corr-attachment-" + suffix);
    }
}
