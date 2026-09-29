package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.provider.ProviderAttachmentUploadMetadata;
import com.unifiedsupportinbox.provider.SafeProviderFileDownloader;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackProviderAttachmentGatewayTests {

    private ProviderIntegrationCredentialLookup integrations;
    private ConfiguredProviderSecretResolver secrets;
    private SlackAttachmentApiClient slack;
    private SlackProviderAttachmentGateway gateway;
    private UUID integrationId;

    @BeforeEach
    void setUp() {
        integrations = mock(ProviderIntegrationCredentialLookup.class);
        secrets = mock(ConfiguredProviderSecretResolver.class);
        slack = mock(SlackAttachmentApiClient.class);
        gateway = new SlackProviderAttachmentGateway(
                integrations, secrets, slack, new SafeProviderFileDownloader());
        integrationId = UUID.randomUUID();
        when(integrations.findForProvider(IntegrationProvider.SLACK)).thenReturn(List.of(
                new CredentialReference(integrationId, "T123", "slack/workspace-one")));
    }

    @Test
    void uploadsBytesAndCompletesIntoRequestedThread() {
        byte[] token = credential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        URI uploadUri = URI.create("https://files.slack.com/upload/v1/example");
        when(slack.getUploadUrl(any(byte[].class), eq("invoice.pdf"), eq(3L)))
                .thenReturn(new SlackAttachmentApiClient.UploadUrlResponse(
                        200, true, "F123", uploadUri, null, null));
        when(slack.uploadBytes(eq(uploadUri), any(byte[].class)))
                .thenReturn(new SlackAttachmentApiClient.UploadBytesResponse(200, null, null));
        when(slack.completeUpload(
                any(byte[].class), eq("F123"), eq("invoice.pdf"), eq("C123"), eq("1712000000.000001")))
                .thenReturn(new SlackAttachmentApiClient.CompleteUploadResponse(
                        200, true, "F123", null, null));

        ProviderAttachmentGateway.UploadedAttachment result = gateway.upload(new ProviderAttachmentGateway.UploadRequest(
                integrationId,
                "C123",
                "1712000000.000001",
                new ProviderAttachmentUploadMetadata("invoice.pdf", "application/pdf", 3),
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3}),
                "message:attachment"));

        assertThat(result.providerFileId()).isEqualTo("F123");
        assertThat(token).containsOnly((byte) 0);
        verify(slack).completeUpload(
                any(byte[].class), eq("F123"), eq("invoice.pdf"), eq("C123"), eq("1712000000.000001"));
    }

    @Test
    void preservesSlackRetryAfterOnUploadUrlRateLimit() {
        byte[] token = credential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.getUploadUrl(any(byte[].class), eq("invoice.pdf"), eq(3L)))
                .thenReturn(new SlackAttachmentApiClient.UploadUrlResponse(
                        429, false, null, null, "rate_limited", Duration.ofSeconds(7)));

        ProviderAttachmentGateway.UploadRequest request = new ProviderAttachmentGateway.UploadRequest(
                integrationId,
                "C123",
                null,
                new ProviderAttachmentUploadMetadata("invoice.pdf", "application/pdf", 3),
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3}),
                "message:attachment");

        assertThatThrownBy(() -> gateway.upload(request))
                .isInstanceOfSatisfying(ProviderAttachmentException.class, failure -> {
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(failure.errorCode()).isEqualTo("SLACK_RATE_LIMITED");
                });
        assertThat(token).containsOnly((byte) 0);
    }

    private static byte[] credential() {
        return "fixture-provider-credential".getBytes(StandardCharsets.US_ASCII);
    }
}
