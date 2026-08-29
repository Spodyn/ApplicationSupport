package com.unifiedsupportinbox.provider.telegram.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class TelegramWebhookAuthenticator {
    static final String WEBHOOK_SECRET_FILE = "telegram-webhook-secret-token";
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;

    TelegramWebhookAuthenticator(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets) {
        this.integrations = integrations;
        this.secrets = secrets;
    }

    CredentialReference authenticate(String header) {
        if (header == null || header.isBlank() || header.length() > 256) {
            throw ApiProblemException.authenticationRequired();
        }
        byte[] supplied = header.getBytes(StandardCharsets.US_ASCII);
        try {
            List<CredentialReference> matches = integrations.findForProvider(IntegrationProvider.TELEGRAM)
                    .stream()
                    .filter(reference -> matches(reference, supplied))
                    .toList();
            if (matches.size() != 1) {
                throw ApiProblemException.authenticationRequired();
            }
            return matches.getFirst();
        } finally {
            Arrays.fill(supplied, (byte) 0);
        }
    }

    private boolean matches(CredentialReference reference, byte[] supplied) {
        return secrets.resolve(reference.secretRef(), WEBHOOK_SECRET_FILE).map(secret -> {
            try {
                return MessageDigest.isEqual(secret, supplied);
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }).orElse(false);
    }
}
