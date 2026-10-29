package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.InvalidCursorException;
import com.unifiedsupportinbox.cases.CaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared Case inbox projection; ownership affects actions, never visibility. */
@Service
class CaseListService {
    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;

    CaseListService(JdbcTemplate jdbc, CursorCodec cursors) {
        this.jdbc = jdbc;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    CursorPage<CaseListItem> list(UUID userId, String cursor, Integer requestedLimit) {
        return list(userId, cursor, requestedLimit, CaseListView.ACTIVE);
    }

    @Transactional(readOnly = true)
    CursorPage<CaseListItem> list(
            UUID userId,
            String cursor,
            Integer requestedLimit,
            CaseListView requestedView) {
        requireEligibleUser(userId);
        CaseListView view = requestedView == null ? CaseListView.ACTIVE : requestedView;
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        CursorPosition position = cursor == null ? null : cursors.decode(cursor, scope(userId, view));
        if (position != null && !anchorStillVisible(userId, view, position)) {
            throw new InvalidCursorException(InvalidCursorException.Reason.SCOPE_MISMATCH);
        }

        List<CaseListItem> fetched = jdbc.query("""
                WITH base AS (
                    SELECT c.id, c.reference, c.status, c.owner_user_id,
                           owner.display_name AS owner_display_name,
                           c.created_at, c.updated_at, c.last_activity_at, c.waiting_until,
                           customer.id AS customer_id, customer.name AS customer_name,
                           channel.id AS channel_id, channel.name AS channel_name,
                           channel.external_channel_id, integration.provider,
                           latest.id AS last_message_id, LEFT(latest.body, 240) AS last_message_preview,
                           reads.last_read_message_id, snoozes.until_at AS snoozed_until,
                           sla.state AS sla_state,
                           CASE WHEN c.status IN ('NEW', 'PARTIALLY_IGNORED')
                                  THEN LEAST(sla.first_response_due_at, sla.unclaimed_warning_at, sla.unclaimed_breach_at)
                                WHEN c.status = 'VERIFICATION'
                                  THEN LEAST(sla.first_response_due_at, sla.in_progress_warning_at, sla.in_progress_breach_at)
                                ELSE NULL END AS sla_due_at,
                           COALESCE(votes.points, 0) AS ignore_score,
                           EXISTS (
                               SELECT 1 FROM messages inbound
                               LEFT JOIN messages marker ON marker.id = reads.last_read_message_id
                               WHERE inbound.case_id = c.id AND inbound.inbound
                                 AND (marker.id IS NULL OR
                                      (COALESCE(inbound.provider_created_at, inbound.created_at), inbound.id)
                                      > (COALESCE(marker.provider_created_at, marker.created_at), marker.id))
                           ) AS unread
                    FROM cases c
                    JOIN customers customer ON customer.id = c.customer_id
                    JOIN channels channel ON channel.id = c.channel_id
                    JOIN integrations integration ON integration.id = c.integration_id
                    LEFT JOIN users owner ON owner.id = c.owner_user_id
                    LEFT JOIN case_read_states reads ON reads.case_id = c.id AND reads.user_id = ?
                    LEFT JOIN case_snoozes snoozes ON snoozes.case_id = c.id AND snoozes.user_id = ?
                    LEFT JOIN case_sla sla ON sla.case_id = c.id
                    LEFT JOIN LATERAL (
                        SELECT id, body FROM messages
                        WHERE case_id = c.id
                        ORDER BY COALESCE(provider_created_at, created_at) DESC, id DESC LIMIT 1
                    ) latest ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT SUM(weight)::int AS points FROM case_ignore_votes
                        WHERE case_id = c.id AND active
                    ) votes ON TRUE
                ), filtered AS (
                    SELECT base.*
                    FROM base
                    WHERE CASE WHEN ?::boolean
                        THEN status NOT IN ('IGNORED', 'RESOLVED')
                             AND snoozed_until > CURRENT_TIMESTAMP
                        ELSE status IN ('IGNORED', 'RESOLVED')
                             OR snoozed_until IS NULL
                             OR snoozed_until <= CURRENT_TIMESTAMP
                    END
                ), ranked AS (
                    SELECT filtered.*,
                           CASE WHEN status IN ('IGNORED', 'RESOLVED') THEN 4
                                WHEN sla_state = 'BREACHED' THEN 0
                                WHEN sla_state = 'WARNING' THEN 1
                                WHEN status = 'NEW' AND unread THEN 2
                                ELSE 3 END AS sort_bucket,
                           COALESCE(sla_due_at, 'infinity'::timestamptz) AS sort_due
                    FROM filtered
                ), anchor AS (
                    SELECT sort_bucket, sort_due, last_activity_at, id
                    FROM ranked WHERE id = ? AND last_activity_at = ?
                )
                SELECT r.* FROM ranked r
                WHERE ?::uuid IS NULL OR EXISTS (
                    SELECT 1 FROM anchor a
                    WHERE r.sort_bucket > a.sort_bucket
                       OR (r.sort_bucket = a.sort_bucket AND r.sort_due > a.sort_due)
                       OR (r.sort_bucket = a.sort_bucket AND r.sort_due = a.sort_due
                           AND r.last_activity_at < a.last_activity_at)
                       OR (r.sort_bucket = a.sort_bucket AND r.sort_due = a.sort_due
                           AND r.last_activity_at = a.last_activity_at AND r.id < a.id)
                )
                ORDER BY r.sort_bucket, r.sort_due, r.last_activity_at DESC, r.id DESC
                LIMIT ?
                """, (rs, ignored) -> item(rs), userId, userId,
                view == CaseListView.SNOOZED,
                position == null ? null : position.id(),
                position == null ? null : OffsetDateTime.ofInstant(position.sortValue(), java.time.ZoneOffset.UTC),
                position == null ? null : position.id(), limit + 1);

        boolean hasMore = fetched.size() > limit;
        List<CaseListItem> page = hasMore ? fetched.subList(0, limit) : fetched;
        String next = hasMore
                ? cursors.encode(
                        new CursorPosition(page.getLast().lastActivityAt(), page.getLast().id()),
                        scope(userId, view))
                : null;
        return new CursorPage<>(page, next);
    }

    private void requireEligibleUser(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE id = ? AND active = TRUE
                  AND role IN ('USER', 'ADMIN')
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, userId);
        if (count == null || count != 1) throw ApiProblemException.accessDenied();
    }

    private boolean anchorStillVisible(UUID userId, CaseListView view, CursorPosition position) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM cases c
                LEFT JOIN case_snoozes snoozes
                  ON snoozes.case_id = c.id AND snoozes.user_id = ?
                WHERE c.id = ?
                  AND c.last_activity_at = ?
                  AND CASE WHEN ?::boolean
                      THEN c.status NOT IN ('IGNORED', 'RESOLVED')
                           AND snoozes.until_at > CURRENT_TIMESTAMP
                      ELSE c.status IN ('IGNORED', 'RESOLVED')
                           OR snoozes.until_at IS NULL
                           OR snoozes.until_at <= CURRENT_TIMESTAMP
                  END
                """, Integer.class,
                userId,
                position.id(),
                OffsetDateTime.ofInstant(position.sortValue(), java.time.ZoneOffset.UTC),
                view == CaseListView.SNOOZED);
        return count != null && count == 1;
    }

    private static CaseListItem item(ResultSet rs) throws SQLException {
        return new CaseListItem(
                rs.getObject("id", UUID.class), rs.getString("reference"), CaseStatus.valueOf(rs.getString("status")),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                rs.getString("external_channel_id"), rs.getString("provider"),
                rs.getObject("owner_user_id", UUID.class), rs.getString("owner_display_name"),
                rs.getObject("last_message_id", UUID.class), rs.getString("last_message_preview"),
                rs.getBoolean("unread"), instant(rs, "snoozed_until"), rs.getString("sla_state"),
                instant(rs, "sla_due_at"), rs.getInt("ignore_score"), instant(rs, "waiting_until"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "last_activity_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String scope(UUID userId, CaseListView view) {
        return "case-list:" + userId + ":" + view.name();
    }

    record CaseListItem(UUID id, String reference, CaseStatus status,
            UUID customerId, String customerName, UUID channelId, String channelName,
            String externalChannelId, String provider, UUID ownerUserId, String ownerDisplayName,
            UUID lastMessageId, String lastMessagePreview, boolean unreadForCurrentUser,
            Instant snoozedUntil, String slaState, Instant slaDueAt, int ignoreScore,
            Instant waitingUntil, Instant createdAt, Instant updatedAt, Instant lastActivityAt) {}
}
