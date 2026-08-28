package com.unifiedsupportinbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned HMAC-signed opaque cursor codec. The caller supplies key material
 * from a protected runtime secret boundary; no signing key is checked into the repository.
 */
public final class CursorCodec {

    static final byte CURRENT_VERSION = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SCOPE_DIGEST_BYTES = 32;
    private static final int SIGNATURE_BYTES = 32;
    private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Long.BYTES + Integer.BYTES
            + Long.BYTES + Long.BYTES + SCOPE_DIGEST_BYTES;
    private static final int MAX_TOKEN_CHARS = 512;
    private static final int MAX_SCOPE_CHARS = 1024;
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(1);

    private final byte[] signingKey;
    private final Clock clock;
    private final Duration ttl;

    public CursorCodec(byte[] signingKey, Clock clock) {
        this(signingKey, clock, ApiV1Conventions.CURSOR_TTL);
    }

    CursorCodec(byte[] signingKey, Clock clock, Duration ttl) {
        Objects.requireNonNull(signingKey, "signingKey");
        if (signingKey.length < 32) {
            throw new IllegalArgumentException("cursor signing key must contain at least 32 bytes");
        }
        this.signingKey = Arrays.copyOf(signingKey, signingKey.length);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("cursor TTL must be positive");
        }
    }

    public String encode(CursorPosition position, String scope) {
        Objects.requireNonNull(position, "position");
        byte[] scopeDigest = scopeDigest(scope);
        Instant issuedAt = clock.instant();

        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES);
        payload.put(CURRENT_VERSION);
        payload.putLong(issuedAt.getEpochSecond());
        payload.putLong(position.sortValue().getEpochSecond());
        payload.putInt(position.sortValue().getNano());
        payload.putLong(position.id().getMostSignificantBits());
        payload.putLong(position.id().getLeastSignificantBits());
        payload.put(scopeDigest);

        byte[] payloadBytes = payload.array();
        return encodeBase64(payloadBytes) + "." + encodeBase64(sign(payloadBytes));
    }

    public CursorPosition decode(String token, String expectedScope) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) {
            throw invalid(InvalidCursorException.Reason.MALFORMED);
        }

        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.') || separator == token.length() - 1) {
            throw invalid(InvalidCursorException.Reason.MALFORMED);
        }

        byte[] payload = decodeBase64(token.substring(0, separator));
        byte[] signature = decodeBase64(token.substring(separator + 1));
        if (payload.length != PAYLOAD_BYTES || signature.length != SIGNATURE_BYTES) {
            throw invalid(InvalidCursorException.Reason.MALFORMED);
        }
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw invalid(InvalidCursorException.Reason.INVALID_SIGNATURE);
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte version = buffer.get();
        if (version != CURRENT_VERSION) {
            throw invalid(InvalidCursorException.Reason.UNSUPPORTED_VERSION);
        }

        try {
            Instant issuedAt = Instant.ofEpochSecond(buffer.getLong());
            Instant now = clock.instant();
            if (issuedAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
                throw invalid(InvalidCursorException.Reason.MALFORMED);
            }
            if (!now.isBefore(issuedAt.plus(ttl))) {
                throw invalid(InvalidCursorException.Reason.EXPIRED);
            }

            Instant sortValue = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            byte[] actualScopeDigest = new byte[SCOPE_DIGEST_BYTES];
            buffer.get(actualScopeDigest);
            if (!MessageDigest.isEqual(actualScopeDigest, scopeDigest(expectedScope))) {
                throw invalid(InvalidCursorException.Reason.SCOPE_MISMATCH);
            }
            return new CursorPosition(sortValue, id);
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalid(InvalidCursorException.Reason.MALFORMED);
        }
    }

    private byte[] scopeDigest(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > MAX_SCOPE_CHARS) {
            throw new IllegalArgumentException("cursor scope must be non-blank and bounded");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(scope.getBytes(UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String encodeBase64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(InvalidCursorException.Reason.MALFORMED);
        }
    }

    private InvalidCursorException invalid(InvalidCursorException.Reason reason) {
        return new InvalidCursorException(reason);
    }
}
