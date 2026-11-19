package com.unifiedsupportinbox.provider.teams.internal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.unifiedsupportinbox.ApiProblemException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Public-cloud Bot Connector JWT trust. Never trusts a payload-selected issuer or JWKS URL. */
@Component
class TeamsJwtAuthenticator {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TeamsJwtAuthenticator.class);
    private final String clientId, tenantId;
    private final Supplier<SigningKeys> keys;
    private final Clock clock;

    @Autowired TeamsJwtAuthenticator(@Value("${usi.providers.teams.client-id:}") String clientId,
            @Value("${usi.providers.teams.tenant-id:}") String tenantId) {
        this(clientId, tenantId, new MicrosoftKeys(), Clock.systemUTC());
    }
    TeamsJwtAuthenticator(String clientId, String tenantId, Supplier<SigningKeys> keys, Clock clock) {
        this.clientId = clientId; this.tenantId = tenantId; this.keys = keys; this.clock = clock;
    }
    void authenticate(String authorization, JsonNode activity) {
        String stage = "TOKEN_FORMAT";
        try {
            if (clientId.isBlank() || tenantId.isBlank() || authorization == null
                    || !authorization.startsWith("Bearer ") || authorization.length() > 16384) throw denied();
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7));
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) throw denied();
            stage = "SIGNING_KEY_OR_SIGNATURE";
            var trusted = keys.get();
            if (trusted.set().getKeyByKeyId(jwt.getHeader().getKeyID()) == null && keys instanceof MicrosoftKeys microsoft)
                trusted = microsoft.refreshForUnknownKey();
            var key = trusted.set().getKeyByKeyId(jwt.getHeader().getKeyID());
            if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa))) throw denied();
            Object endorsements = trusted.endorsements().get(jwt.getHeader().getKeyID());
            if (endorsements != null && (!(endorsements instanceof List<?> list) || !list.contains("msteams"))) throw denied();
            stage = "TOKEN_CLAIMS";
            var claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!"https://api.botframework.com".equals(claims.getIssuer())
                    || !claims.getAudience().contains(clientId)
                    || claims.getExpirationTime() == null || claims.getNotBeforeTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(now.minusSeconds(60))
                    || claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(60))) throw denied();
            String serviceUrl = activity.path("serviceUrl").asText("");
            stage = "SERVICE_URL_BINDING";
            if (!serviceUrl.equals(claims.getStringClaim("serviceurl")) || !safeServiceUrl(serviceUrl)) throw denied();
            stage = "CHANNEL_BINDING";
            if (!"msteams".equals(activity.path("channelId").asText())) throw denied();
            stage = "TENANT_BINDING";
            if (!tenantId.equals(activity.path("channelData").path("tenant").path("id").asText())) throw denied();
            stage = "RECIPIENT_BINDING";
            if (!List.of(clientId, "28:" + clientId).contains(activity.path("recipient").path("id").asText())) throw denied();
        } catch (Exception failure) {
            // Provider key/token details must never reach logs or problem responses.
            LOGGER.warn("Teams callback authentication rejected; stage={}", stage);
            throw denied();
        }
    }
    static boolean safeServiceUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() == -1
                    && uri.getQuery() == null && uri.getFragment() == null
                    && List.of("smba.trafficmanager.net", "smba.infra.teams.microsoft.com").contains(uri.getHost())
                    && uri.getRawPath().matches("/[A-Za-z0-9/-]*");
        } catch (RuntimeException invalid) { return false; }
    }
    private static ApiProblemException denied() { return ApiProblemException.authenticationRequired(); }

    record SigningKeys(JWKSet set, java.util.Map<String,Object> endorsements) { }
    static final class MicrosoftKeys implements Supplier<SigningKeys> {
        private final HttpClient http;
        private final URI endpoint;
        MicrosoftKeys() {
            this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build(),
                    URI.create("https://login.botframework.com/v1/.well-known/keys"));
        }
        MicrosoftKeys(HttpClient http, URI endpoint) { this.http=http; this.endpoint=endpoint; }
        private SigningKeys cached;
        private Instant expires = Instant.EPOCH;
        private Instant fetched = Instant.EPOCH;
        synchronized SigningKeys refreshForUnknownKey() {
            if (Instant.now().isAfter(fetched.plusSeconds(30))) expires = Instant.EPOCH;
            return get();
        }
        @Override public synchronized SigningKeys get() {
            if (cached != null && Instant.now().isBefore(expires)) return cached;
            try {
                var request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10)).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body=response.body()) {
                    // Microsoft currently publishes hundreds of keys (~776 KiB). Keep a bounded
                    // read with room for normal rotation, rather than rejecting the valid key set.
                    byte[] encoded=body.readNBytes(2 * 1024 * 1024 + 1);
                    if (response.statusCode() != 200 || encoded.length > 2 * 1024 * 1024) throw denied();
                    String payload=new String(encoded,java.nio.charset.StandardCharsets.UTF_8);
                    var raw = com.nimbusds.jose.util.JSONObjectUtils.parse(payload);
                    var endorsements = new java.util.HashMap<String,Object>();
                    for (Object item : (List<?>) raw.get("keys")) {
                        if (item instanceof java.util.Map<?,?> map && map.get("kid") instanceof String kid && map.containsKey("endorsements"))
                            endorsements.put(kid,map.get("endorsements"));
                    }
                    cached = new SigningKeys(JWKSet.parse(payload),endorsements); expires = Instant.now().plusSeconds(3600);
                    fetched = Instant.now();
                    return cached;
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw denied(); }
            catch (Exception failure) { throw denied(); }
        }
    }
}
