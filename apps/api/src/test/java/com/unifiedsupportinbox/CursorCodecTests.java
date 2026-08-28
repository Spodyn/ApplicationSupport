package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CursorCodecTests {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final String SCOPE = "case-feed:v1|status=new|sort=createdAt:desc";

    @Test
    void signedCursorRoundTripsWithoutExposingScopeOrIdentifierText() {
        byte[] key = key();
        CursorCodec codec = new CursorCodec(key, Clock.fixed(NOW, ZoneOffset.UTC));
        CursorPosition position = new CursorPosition(
                Instant.parse("2026-08-28T11:59:00.123456Z"),
                UUID.fromString("018f7777-1111-7aaa-8bbb-ccccdddd0001"));

        String token = codec.encode(position, SCOPE);

        assertThat(codec.decode(token, SCOPE)).isEqualTo(position);
        assertThat(token).doesNotContain(SCOPE).doesNotContain(position.id().toString());
        assertThat(token).doesNotContain("=");
    }

    @Test
    void rejectsMalformedTamperedExpiredUnsupportedAndWrongScopeCursors() throws Exception {
        byte[] key = key();
        CursorCodec codec = new CursorCodec(key, Clock.fixed(NOW, ZoneOffset.UTC));
        CursorPosition position = new CursorPosition(NOW.minusSeconds(10), uuid(1));
        String valid = codec.encode(position, SCOPE);

        assertReason(() -> codec.decode("not-a-cursor", SCOPE), InvalidCursorException.Reason.MALFORMED);
        assertReason(() -> codec.decode(tamperSignature(valid), SCOPE), InvalidCursorException.Reason.INVALID_SIGNATURE);
        assertReason(() -> codec.decode(valid, "case-feed:v1|status=resolved|sort=createdAt:desc"),
                InvalidCursorException.Reason.SCOPE_MISMATCH);

        CursorCodec expiredCodec = new CursorCodec(
                key,
                Clock.fixed(NOW.plus(ApiV1Conventions.CURSOR_TTL), ZoneOffset.UTC));
        assertReason(() -> expiredCodec.decode(valid, SCOPE), InvalidCursorException.Reason.EXPIRED);

        assertReason(() -> codec.decode(withSignedVersion(valid, key, (byte) 2), SCOPE),
                InvalidCursorException.Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void tupleOrderingHandlesEqualTimestampsWithoutDuplicatesOrGapsAcrossPages() {
        byte[] key = key();
        CursorCodec codec = new CursorCodec(key, Clock.fixed(NOW, ZoneOffset.UTC));
        Instant equal = Instant.parse("2026-08-28T10:00:00Z");
        List<CursorPosition> source = List.of(
                new CursorPosition(equal.plusSeconds(1), uuid(1)),
                new CursorPosition(equal, uuid(4)),
                new CursorPosition(equal, uuid(2)),
                new CursorPosition(equal.minusSeconds(1), uuid(9)),
                new CursorPosition(equal, uuid(3)));

        List<CursorPosition> expected = source.stream()
                .sorted(CursorDirection.DESC.comparator())
                .toList();
        List<CursorPosition> collected = new ArrayList<>();
        CursorPosition cursor = null;
        int pageSize = 2;

        while (true) {
            CursorPosition currentCursor = cursor;
            List<CursorPosition> remaining = expected.stream()
                    .filter(position -> currentCursor == null
                            || CursorDirection.DESC.isAfter(position, currentCursor))
                    .toList();
            List<CursorPosition> items = remaining.stream().limit(pageSize).toList();
            boolean hasMore = remaining.size() > items.size();
            String nextCursor = hasMore ? codec.encode(items.getLast(), SCOPE) : null;
            CursorPage<CursorPosition> page = new CursorPage<>(items, nextCursor);
            collected.addAll(page.items());

            if (page.nextCursor() == null) {
                break;
            }
            cursor = codec.decode(page.nextCursor(), SCOPE);
        }

        assertThat(collected).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(collected)).hasSameSizeAs(collected);
    }

    @Test
    void emptyAndLastPagesHaveNoNextCursorAndDefensivelyCopyItems() {
        CursorPage<String> empty = new CursorPage<>(List.of(), null);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();

        List<String> mutable = new ArrayList<>(List.of("last"));
        CursorPage<String> last = new CursorPage<>(mutable, null);
        mutable.add("late mutation");
        assertThat(last.items()).containsExactly("last");
        assertThat(last.nextCursor()).isNull();
    }

    private static void assertReason(ThrowingRunnable action, InvalidCursorException.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidCursorException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static String tamperSignature(String token) {
        String[] parts = token.split("\\.", -1);
        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        signature[0] ^= 0x01;
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String withSignedVersion(String token, byte[] key, byte version)
            throws GeneralSecurityException {
        String[] parts = token.split("\\.", -1);
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        payload[0] = version;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] signature = mac.doFinal(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5A);
        return key;
    }

    private static UUID uuid(long suffix) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(0x018f777711117aaaL);
        bytes.putLong(0x8bbbcccc00000000L | suffix);
        bytes.flip();
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
