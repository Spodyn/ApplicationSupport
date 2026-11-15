package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
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
    CaseListPage list(UUID userId, String cursor, Integer requestedLimit) {
        return list(userId, cursor, requestedLimit, CaseListView.ACTIVE, CaseListFilter.empty());
    }

    @Transactional(readOnly = true)
    CaseListPage list(
            UUID userId,
            String cursor,
            Integer requestedLimit,
            CaseListView requestedView) {
        return list(userId, cursor, requestedLimit, requestedView, CaseListFilter.empty());
    }

    @Transactional(readOnly = true)
    CaseListPage list(
            UUID userId,
            String cursor,
            Integer requestedLimit,
            CaseListView requestedView,
            CaseListFilter filter) {
        return list(userId, cursor, requestedLimit, requestedView, filter, QuickView.ALL);
    }

    @Transactional(readOnly = true)
    CaseListPage list(UUID userId, String cursor, Integer requestedLimit,
            CaseListView requestedView, CaseListFilter filter, QuickView requestedQuickView) {
        requireEligibleUser(userId);
        QuickView quickView = requestedQuickView == null ? QuickView.ALL : requestedQuickView;
        CaseListView view = requestedView == null ? CaseListView.ACTIVE : requestedView;
        CaseListFilter applied = filter == null ? CaseListFilter.empty() : filter;
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        CursorPosition position = cursor == null ? null : cursors.decode(cursor, scope(userId, view, applied, quickView));
        if (position != null && !anchorStillVisible(userId, view, position)) {
            throw new InvalidCursorException(InvalidCursorException.Reason.SCOPE_MISMATCH);
        }

        List<PageRow> rows = jdbc.query("""
                WITH base AS (
                    SELECT c.id, c.reference, c.status, c.owner_user_id,
                           owner.display_name AS owner_display_name,
                           c.created_at, c.updated_at, c.last_activity_at, c.waiting_until,
                           COALESCE(customer.id, '00000000-0000-0000-0000-000000000000'::uuid) AS customer_id, COALESCE(customer.name, 'Nie przypisano') AS customer_name,
                           channel.id AS channel_id, channel.name AS channel_name,
                           channel.external_channel_id, integration.provider,
                           integration.workspace_external_id, c.external_thread_key,
                           latest.id AS last_message_id, CASE WHEN latest.deleted_at IS NOT NULL THEN 'Wiadomość została usunięta w Slack.' ELSE LEFT(latest.body, 240) END AS last_message_preview,
                           latest.sent_at AS last_message_at, latest.author AS last_message_author,
                           latest.kind AS last_message_kind, latest_file.original_filename AS last_message_file,
                           reads.last_read_message_id, snoozes.until_at AS snoozed_until,
                           CASE WHEN sla.paused_at IS NOT NULL THEN 'PAUSED' ELSE sla.state END AS sla_state,
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
                    LEFT JOIN customers customer ON customer.id = c.customer_id
                    JOIN channels channel ON channel.id = c.channel_id
                    JOIN integrations integration ON integration.id = c.integration_id
                    LEFT JOIN users owner ON owner.id = c.owner_user_id
                    LEFT JOIN case_read_states reads ON reads.case_id = c.id AND reads.user_id = ?
                    LEFT JOIN case_snoozes snoozes ON snoozes.case_id = c.id AND snoozes.user_id = ? AND snoozes.until_at > CURRENT_TIMESTAMP
                    LEFT JOIN case_sla sla ON sla.case_id = c.id
                    LEFT JOIN LATERAL (
                        SELECT m.id, m.body, m.deleted_at, m.kind,
                               COALESCE(m.provider_created_at, m.created_at) AS sent_at,
                               COALESCE(NULLIF(m.author_name, ''), sender.display_name, m.author_external_id) AS author
                        FROM messages m
                        LEFT JOIN users sender ON sender.id = m.author_user_id
                        WHERE m.case_id = c.id
                        ORDER BY COALESCE(m.provider_created_at, m.created_at) DESC, m.id DESC LIMIT 1
                    ) latest ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT original_filename FROM attachments
                        WHERE message_id = latest.id AND latest.deleted_at IS NULL
                        ORDER BY created_at, id LIMIT 1
                    ) latest_file ON TRUE
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
                      AND (?::boolean = FALSE OR owner_user_id = ?::uuid)
                      AND (?::boolean = FALSE OR owner_user_id IS NULL)
                      AND (?::boolean = FALSE OR unread)
                      AND (?::boolean = FALSE OR (status NOT IN ('IGNORED', 'RESOLVED', 'WAITING_FOR_CUSTOMER')
                          AND sla_state IN ('WARNING', 'BREACHED')))
                      AND (?::varchar IS NULL OR status = ?::varchar)
                      AND (?::varchar = 'ALL'
                           OR (?::varchar = 'ACTIVE' AND status NOT IN ('IGNORED', 'RESOLVED'))
                           OR (?::varchar = 'TERMINAL' AND status IN ('IGNORED', 'RESOLVED')))
                      AND (?::uuid IS NULL OR customer_id = ?::uuid)
                      AND (?::uuid IS NULL OR channel_id = ?::uuid)
                      AND (?::varchar IS NULL OR provider = ?::varchar)
                      AND (?::uuid IS NULL OR owner_user_id = ?::uuid)
                      AND (?::varchar IS NULL OR
                           lower(reference) LIKE ?::varchar ESCAPE '\\'
                           OR lower(customer_name) LIKE ?::varchar ESCAPE '\\'
                           OR lower(channel_name) LIKE ?::varchar ESCAPE '\\'
                           OR lower(external_channel_id) LIKE ?::varchar ESCAPE '\\'
                           OR lower(COALESCE(external_thread_key, '')) LIKE ?::varchar ESCAPE '\\'
                           OR lower(COALESCE(workspace_external_id, '')) LIKE ?::varchar ESCAPE '\\'
                           OR lower(provider) LIKE ?::varchar ESCAPE '\\')
                ), views AS (
                    SELECT filtered.*,
                           status NOT IN ('IGNORED', 'RESOLVED') AND owner_user_id IS NULL AS quick_unassigned,
                           status NOT IN ('IGNORED', 'RESOLVED') AND owner_user_id = ?::uuid AS quick_mine,
                           status NOT IN ('IGNORED', 'RESOLVED', 'WAITING_FOR_CUSTOMER')
                               AND sla_state IN ('WARNING', 'BREACHED') AS quick_sla
                    FROM filtered
                ), totals AS (
                    SELECT count(*) AS all_count, count(*) FILTER (WHERE quick_unassigned) AS unassigned_count,
                           count(*) FILTER (WHERE quick_mine) AS mine_count,
                           count(*) FILTER (WHERE quick_sla) AS sla_count
                    FROM views
                ), ranked AS (
                    SELECT views.*,
                           CASE WHEN status IN ('IGNORED', 'RESOLVED') THEN 4
                                WHEN status <> 'WAITING_FOR_CUSTOMER' AND sla_state = 'BREACHED' THEN 0
                                WHEN status <> 'WAITING_FOR_CUSTOMER' AND sla_state = 'WARNING' THEN 1
                                WHEN status = 'NEW' AND unread THEN 2
                                ELSE 3 END AS sort_bucket,
                           COALESCE(sla_due_at, 'infinity'::timestamptz) AS sort_due
                    FROM views
                    WHERE CASE ?::varchar
                        WHEN 'UNASSIGNED' THEN quick_unassigned
                        WHEN 'MINE' THEN quick_mine
                        WHEN 'SLA' THEN quick_sla
                        ELSE TRUE END
                ), anchor AS (
                    SELECT sort_bucket, sort_due, last_activity_at, id
                    FROM ranked WHERE id = ? AND last_activity_at = ?
                ), page AS (
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
                )
                SELECT page.*, totals.* FROM totals LEFT JOIN page ON TRUE
                ORDER BY page.sort_bucket, page.sort_due, page.last_activity_at DESC, page.id DESC
                """, (rs, ignored) -> new PageRow(
                        rs.getObject("id") == null ? null : item(rs),
                        new QuickViewCounts(rs.getLong("unassigned_count"), rs.getLong("mine_count"),
                                rs.getLong("sla_count"), rs.getLong("all_count"))), userId, userId,
                view == CaseListView.SNOOZED,
                applied.mine(), userId,
                applied.unassigned(),
                applied.unread(),
                applied.slaAttention(),
                applied.status() == null ? null : applied.status().name(),
                applied.status() == null ? null : applied.status().name(),
                applied.statusScope().name(), applied.statusScope().name(), applied.statusScope().name(),
                applied.customerId(), applied.customerId(),
                applied.channelId(), applied.channelId(),
                applied.provider(), applied.provider(),
                applied.ownerId(), applied.ownerId(),
                applied.search(), searchPattern(applied.search()), searchPattern(applied.search()),
                searchPattern(applied.search()), searchPattern(applied.search()), searchPattern(applied.search()),
                searchPattern(applied.search()), searchPattern(applied.search()),
                userId, quickView.name(),
                position == null ? null : position.id(),
                position == null ? null : OffsetDateTime.ofInstant(position.sortValue(), java.time.ZoneOffset.UTC),
                position == null ? null : position.id(), limit + 1);

        List<CaseListItem> fetched = rows.stream().map(PageRow::item).filter(java.util.Objects::nonNull).toList();
        boolean hasMore = fetched.size() > limit;
        List<CaseListItem> page = hasMore ? fetched.subList(0, limit) : fetched;
        String next = hasMore
                ? cursors.encode(
                        new CursorPosition(page.getLast().lastActivityAt(), page.getLast().id()),
                        scope(userId, view, applied, quickView))
                : null;
        return new CaseListPage(page, next, rows.getFirst().counts());
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
                instant(rs, "last_message_at"), rs.getString("last_message_author"),
                rs.getString("last_message_kind"), rs.getString("last_message_file"),
                rs.getBoolean("unread"), instant(rs, "snoozed_until"), rs.getString("sla_state"),
                instant(rs, "sla_due_at"), rs.getInt("ignore_score"), instant(rs, "waiting_until"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "last_activity_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String searchPattern(String search) {
        return search == null ? null : "%" + search + "%";
    }

    private static String scope(UUID userId, CaseListView view, CaseListFilter filter, QuickView quickView) {
        String scope = "case-list:" + userId + ":" + view.name() + ":" + filter.scopeToken();
        return quickView == QuickView.ALL ? scope : scope + ":" + quickView.name();
    }

    enum QuickView { UNASSIGNED, MINE, SLA, ALL }

    record QuickViewCounts(long unassigned, long mine, long sla, long all) {}
    record CaseListPage(List<CaseListItem> items, String nextCursor, QuickViewCounts quickViewCounts) {}
    private record PageRow(CaseListItem item, QuickViewCounts counts) {}

    record CaseListItem(UUID id, String reference, CaseStatus status,
            UUID customerId, String customerName, UUID channelId, String channelName,
            String externalChannelId, String provider, UUID ownerUserId, String ownerDisplayName,
            UUID lastMessageId, String lastMessagePreview,
            Instant lastMessageAt, String lastMessageAuthor, String lastMessageKind, String lastMessageFile,
            boolean unreadForCurrentUser,
            Instant snoozedUntil, String slaState, Instant slaDueAt, int ignoreScore,
            Instant waitingUntil, Instant createdAt, Instant updatedAt, Instant lastActivityAt) {}
}
