package com.unifiedsupportinbox;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi")
@Validated
public record UsiConfigurationProperties(
        @Valid @NotNull Deployment deployment,
        @NotNull URI publicBaseUrl,
        @Valid @NotNull ProviderCallbacks providerCallbacks,
        @Valid @NotNull Security security,
        @Valid @NotNull ObjectStorage objectStorage,
        @Valid @NotNull IntegrationSecrets integrationSecrets) {

    private static final String SLACK_EVENTS_PATH = "/api/v1/providers/slack/events";

    public enum DeploymentProfile {
        LOCAL,
        TEST,
        STAGING,
        PROD,
        PRODUCTION
    }

    public enum SecretBackend {
        FILESYSTEM,
        IN_MEMORY,
        CONFIGTREE
    }

    public record Deployment(@NotNull DeploymentProfile profile) {
    }

    public record ProviderCallbacks(
            @NotNull URI slack,
            @NotNull URI teams,
            @NotNull URI telegram) {

        @AssertTrue(message = "provider callback URLs must use HTTPS under /api/v1 and Slack must use its reviewed events path")
        public boolean isValidCallbacks() {
            return validCallback(slack)
                    && SLACK_EVENTS_PATH.equals(slack.getPath())
                    && validCallback(teams)
                    && validCallback(telegram);
        }

        private static boolean validCallback(URI uri) {
            return uri != null
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && ("/api/v1".equals(uri.getPath()) || uri.getPath().startsWith("/api/v1/"));
        }
    }

    public record Security(@Valid @NotNull Cors cors) {
    }

    public record Cors(List<URI> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    public record ObjectStorage(
            @NotNull URI endpoint,
            @NotBlank String region,
            @NotBlank String attachmentsBucket,
            @NotBlank String exportsBucket) {
    }

    public record IntegrationSecrets(
            @NotNull SecretBackend backend,
            Path directory) {
    }

    @AssertTrue(message = "public and object-storage URLs must be HTTP(S); staging/prod require HTTPS")
    public boolean isValidEnvironmentUrls() {
        if (!isWebUri(publicBaseUrl) || !isWebUri(objectStorage.endpoint())) {
            return false;
        }
        if (isProductionLike()) {
            return isHttps(publicBaseUrl) && isHttps(objectStorage.endpoint());
        }
        return true;
    }

    @AssertTrue(message = "staging/prod CORS origins must use HTTPS")
    public boolean isValidCorsOrigins() {
        for (URI origin : security.cors().allowedOrigins()) {
            if (!isOrigin(origin)) {
                return false;
            }
            if (isProductionLike() && !isHttps(origin)) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "filesystem/configtree secret backends require an absolute integration secret directory")
    public boolean isValidSecretDirectory() {
        if (integrationSecrets.backend() == SecretBackend.IN_MEMORY) {
            return true;
        }
        Path directory = integrationSecrets.directory();
        return directory != null && directory.isAbsolute();
    }

    private boolean isProductionLike() {
        DeploymentProfile profile = deployment.profile();
        return profile == DeploymentProfile.STAGING
                || profile == DeploymentProfile.PROD
                || profile == DeploymentProfile.PRODUCTION;
    }

    private static boolean isWebUri(URI uri) {
        return uri != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null;
    }

    private static boolean isHttps(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme());
    }

    private static boolean isOrigin(URI uri) {
        return isWebUri(uri)
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                && uri.getQuery() == null;
    }
}
