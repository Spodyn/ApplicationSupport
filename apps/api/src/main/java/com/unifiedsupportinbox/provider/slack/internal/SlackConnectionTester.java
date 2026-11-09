package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class SlackConnectionTester implements IntegrationConnectionTester {

    private static final Set<String> UNAUTHORIZED = Set.of(
            "account_inactive", "invalid_auth", "not_authed", "token_expired", "token_revoked",
            "team_access_not_granted", "missing_scope", "no_permission");

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackAdministrationApiClient slack;

    SlackConnectionTester(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackAdministrationApiClient slack) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.slack = slack;
    }

    @Override
    public boolean supports(IntegrationProvider provider) {
        return provider == IntegrationProvider.SLACK;
    }

    @Override
    public Result test(Request request) {
        CredentialReference reference = integrations.findForProvider(IntegrationProvider.SLACK).stream()
                .filter(candidate -> candidate.integrationId().equals(request.integrationId()))
                .findFirst()
                .orElse(null);
        if (reference == null) return new Result(Outcome.UNAUTHORIZED, "SLACK_CREDENTIAL_REFERENCE_MISSING");
        byte[] token = secrets.resolve(reference.secretRef(), SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE)
                .orElse(null);
        if (token == null) return new Result(Outcome.UNAUTHORIZED, "SLACK_BOT_TOKEN_MISSING");
        try {
            SlackAdministrationApiClient.AuthResponse response = slack.testAuthentication(token);
            if (!response.ok()) {
                String error = normalized(response.errorCode());
                Outcome outcome = response.statusCode() == 408 || response.statusCode() == 504
                        ? Outcome.TIMEOUT
                        : UNAUTHORIZED.contains(response.errorCode()) ? Outcome.UNAUTHORIZED : Outcome.UNAVAILABLE;
                return new Result(outcome, error);
            }
            if (response.workspaceId() == null
                    || !response.workspaceId().equals(request.workspaceExternalId())) {
                return new Result(Outcome.UNAUTHORIZED, "SLACK_WORKSPACE_MISMATCH");
            }
            return Result.success();
        } catch (RuntimeException exception) {
            if (hasCause(exception, HttpTimeoutException.class)) return Result.timeout();
            return new Result(Outcome.UNAVAILABLE, "SLACK_CONNECTION_FAILED");
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return "SLACK_INVALID_RESPONSE";
        String code = value.strip().replaceAll("[^A-Za-z0-9_.:-]+", "_").toUpperCase(java.util.Locale.ROOT);
        return code.startsWith("SLACK_") ? code : "SLACK_" + code;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
