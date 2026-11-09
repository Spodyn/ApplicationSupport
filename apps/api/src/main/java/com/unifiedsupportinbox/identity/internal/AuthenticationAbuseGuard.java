package com.unifiedsupportinbox.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable reservations committed before credential verification, including failed requests. */
@Service
class AuthenticationAbuseGuard {
    private final JdbcTemplate jdbc;

    AuthenticationAbuseGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long login(String email, String peer) {
        // Reserve IP first: random account names cannot create unbounded account rows.
        long ipWait = reserve("login-ip", peer, 50, 900, false);
        return ipWait > 0 ? ipWait : reserve("login-account", normalize(email), 10, 900, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reset(String email, String peer) {
        long ipWait = reserve("reset-ip", peer, 20, 3600, false);
        return ipWait > 0 ? ipWait : reserve("reset-account", normalize(email), 5, 3600, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long actionToken(String peer) {
        return reserve("action-ip", peer, 50, 900, false);
    }

    private long reserve(String scope, String identity, int limit, int windowSeconds, boolean backoff) {
        String key = scope + ":" + digest(identity == null ? "" : identity);
        jdbc.update("""
                INSERT INTO authentication_abuse_budgets (bucket_key, attempts, expires_at, next_allowed_at)
                VALUES (?, 0, now() + (? * interval '1 second'), now())
                ON CONFLICT (bucket_key) DO NOTHING
                """, key, windowSeconds);
        Budget budget = jdbc.queryForObject("""
                SELECT attempts, expires_at, next_allowed_at
                FROM authentication_abuse_budgets WHERE bucket_key = ? FOR UPDATE
                """, (rs, row) -> new Budget(rs.getInt("attempts"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("next_allowed_at").toInstant()), key);
        if (budget == null) throw new IllegalStateException("Authentication budget is unavailable.");
        // Transaction-start now() can precede a concurrent reservation even after
        // its row lock is acquired. Read wall time after locking to avoid false backoff.
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        boolean expired = !budget.expiresAt().isAfter(now);
        int attempts = expired ? 0 : budget.attempts();
        Instant expiry = expired ? now.plusSeconds(windowSeconds) : budget.expiresAt();
        if (attempts >= limit) return secondsUntil(now, expiry);
        if (!expired && budget.nextAllowedAt().isAfter(now)) return secondsUntil(now, budget.nextAllowedAt());
        int next = attempts + 1;
        long delay = backoff && next >= 5 ? 1L << (next - 5) : 0;
        jdbc.update("""
                UPDATE authentication_abuse_budgets SET attempts = ?, expires_at = ?, next_allowed_at = ?
                WHERE bucket_key = ?
                """, next, Timestamp.from(expiry), Timestamp.from(now.plusSeconds(delay)), key);
        return 0;
    }

    private static long secondsUntil(Instant now, Instant then) {
        return Math.max(1, (then.toEpochMilli() - now.toEpochMilli() + 999) / 1000);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Budget(int attempts, Instant expiresAt, Instant nextAllowedAt) { }
}
