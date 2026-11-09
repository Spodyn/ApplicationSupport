package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
class IgnoreVoteWeightController {
    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;

    IgnoreVoteWeightController(JdbcTemplate jdbc, AuditEventStore audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PutMapping("/{userId}/ignore-weight")
    @Transactional
    WeightView configure(
            @PathVariable UUID userId,
            @RequestBody WeightRequest body,
            Authentication authentication) {
        UUID actorId = authenticatedAdmin(authentication);
        if (body == null || body.weight() < 1 || body.weight() > 2) {
            throw ApiProblemException.validationFailed("Ignore vote weight must be 1 or 2.");
        }
        if (body.validUntil() != null && !body.validUntil().isAfter(Instant.now())) {
            throw ApiProblemException.validationFailed("Ignore vote weight expiry must be in the future.");
        }
        Integer userCount = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
        if (userCount == null || userCount != 1) throw ApiProblemException.notFound("User was not found.");
        if (body.weight() == 1) {
            jdbc.update("DELETE FROM user_ignore_vote_weights WHERE user_id = ?", userId);
        } else {
            jdbc.update("""
                    INSERT INTO user_ignore_vote_weights (user_id, weight, valid_until, configured_by, updated_at)
                    VALUES (?, 2, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (user_id) DO UPDATE
                    SET weight = 2,
                        valid_until = EXCLUDED.valid_until,
                        configured_by = EXCLUDED.configured_by,
                        updated_at = CURRENT_TIMESTAMP
                    """, userId,
                    body.validUntil() == null ? null : OffsetDateTime.ofInstant(body.validUntil(), ZoneOffset.UTC),
                    actorId);
        }
        audit.append(AuditActorType.ADMIN, actorId, "IGNORE_WEIGHT_UPDATED", "USER", userId, null,
                Map.of("weight", body.weight(), "validUntil", body.validUntil() == null ? "" : body.validUntil().toString()));
        return new WeightView(userId, body.weight(), body.weight() == 1 ? null : body.validUntil());
    }

    private UUID authenticatedAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        UUID actorId;
        try {
            actorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users
                WHERE id = ? AND role = 'ADMIN' AND active
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """,
                Integer.class, actorId);
        if (count == null || count != 1) throw ApiProblemException.accessDenied();
        return actorId;
    }

    record WeightRequest(int weight, Instant validUntil) {}
    record WeightView(UUID userId, int weight, Instant validUntil) {}
}
