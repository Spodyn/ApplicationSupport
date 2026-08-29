package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
class SlackRequestAuthenticator {

    static final String SIGNING_CREDENTIAL_FILE = "slack-signing-secret";
    private static final long REPLAY_WINDOW_SECONDS = 300;
    private static final String SIGNATURE_PREFIX = "v0=";

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;

    SlackRequestAuthenticator(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets) {
        this.integrations = integrations;
        this.secrets = secrets;
    }

    List<CredentialReference> authenticate(
            String timestampHeader,
            String signatureHeader,
            byte[] rawBody) {
        long timestamp = validTimestamp(timestampHeader);
        if (!validSignatureShape(signatureHeader) || rawBody == null) {
            throw ApiProblemException.authenticationRequired();
        }

        long now = Instant.now().getEpochSecond();
        if (timestamp < now - REPLAY_WINDOW_SECONDS || timestamp > now + REPLAY_WINDOW_SECONDS) {
            throw ApiProblemException.authenticationRequired();
        }

        byte[] suppliedSignature = signatureHeader.getBytes(StandardCharsets.US_ASCII);
        List<CredentialReference> matches = new ArrayList<>();
        try {
            for (CredentialReference reference : integrations.findForProvider(IntegrationProvider.SLACK)) {
                secrets.resolve(reference.secretRef(), SIGNING_CREDENTIAL_FILE).ifPresent(secret -> {
                    try {
                        byte[] expected = expectedSignature(secret, timestampHeader, rawBody);
                        try {
                            if (MessageDigest.isEqual(expected, suppliedSignature)) {
                                matches.add(reference);
                            }
                        } finally {
                            Arrays.fill(expected, (byte) 0);
                        }
                    } finally {
                        Arrays.fill(secret, (byte) 0);
                    }
                });
            }
        } finally {
            Arrays.fill(suppliedSignature, (byte) 0);
        }

        if (matches.isEmpty()) {
            throw ApiProblemException.authenticationRequired();
        }
        return List.copyOf(matches);
    }

    private static long validTimestamp(String value) {
        if (value == null || value.isBlank() || value.length() > 20) {
            throw ApiProblemException.authenticationRequired();
        }
        try {
            long timestamp = Long.parseLong(value);
            if (timestamp < 0) throw ApiProblemException.authenticationRequired();
            return timestamp;
        } catch (NumberFormatException exception) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    private static boolean validSignatureShape(String value) {
        if (value == null || value.length() != SIGNATURE_PREFIX.length() + 64 || !value.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        for (int index = SIGNATURE_PREFIX.length(); index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] expectedSignature(byte[] secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(rawBody);
            try {
                return (SIGNATURE_PREFIX + HexFormat.of().formatHex(digest))
                        .getBytes(StandardCharsets.US_ASCII);
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Slack signature verification is unavailable.", exception);
        }
    }
}
