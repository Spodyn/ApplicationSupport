package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class DirectMessageService {
    private static final String ACTIVE = """
            active AND (valid_from IS NULL OR valid_from <= statement_timestamp())
                   AND (valid_until IS NULL OR valid_until > statement_timestamp())
            """;
    private static final String MEMBERSHIP = "(c.low_user_id = :actor OR c.high_user_id = :actor)";
    private static final String READ_POSITION =
            "CASE WHEN c.low_user_id = :actor THEN c.low_read_through ELSE c.high_read_through END";
    private static final String CONVERSATIONS = """
            SELECT c.id, c.updated_at, u.id AS peer_id, u.display_name, (%s) AS peer_active,
                   (SELECT count(*) FROM direct_messages m
                    WHERE m.conversation_id = c.id AND m.sender_id = u.id
                      AND m.position > (%s)) AS unread
            FROM direct_conversations c
            JOIN users u ON u.id = CASE WHEN c.low_user_id = :actor THEN c.high_user_id ELSE c.low_user_id END
            WHERE %s
            """.formatted(ACTIVE, READ_POSITION, MEMBERSHIP);

    private final NamedParameterJdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    DirectMessageService(NamedParameterJdbcTemplate jdbc, CursorCodec cursors,
            IdempotentCommandExecutor idempotency, ObjectMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.idempotency = idempotency;
        this.json = json;
        this.events = events;
    }

    @Transactional(readOnly = true)
    CursorPage<Recipient> recipients(UUID actor, String search, String cursor, Integer requestedLimit) {
        String name = searchTerm(search);
        String scope = "dm-recipients:" + actor + ":" + name;
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        var params = new MapSqlParameterSource().addValue("actor", actor).addValue("name", name)
                .addValue("limit", limit + 1);
        String after = "";
        if (cursor != null) {
            params.addValue("id", cursors.decode(cursor, scope).id());
            after = " AND id > :id";
        }
        var rows = jdbc.query("SELECT id, display_name FROM users WHERE id <> :actor AND " + ACTIVE
                + " AND strpos(lower(display_name), lower(:name)) > 0" + after + " ORDER BY id LIMIT :limit",
                params, (rs, row) -> new Recipient(id(rs, "id"), rs.getString("display_name")));
        return page(rows, limit, scope, recipient -> new CursorPosition(Instant.EPOCH, recipient.id()));
    }

    @Transactional(readOnly = true)
    CursorPage<Conversation> conversations(UUID actor, String search, String cursor, Integer requestedLimit) {
        String name = searchTerm(search);
        String scope = "dm-conversations:" + actor + ":" + name;
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        var params = new MapSqlParameterSource("actor", actor).addValue("limit", limit + 1).addValue("name", name);
        String before = "";
        if (cursor != null) {
            var position = cursors.decode(cursor, scope);
            params.addValue("id", position.id()).addValue("time", OffsetDateTime.ofInstant(position.sortValue(), java.time.ZoneOffset.UTC));
            before = " AND (c.updated_at, c.id) < (:time, :id)";
        }
        var rows = jdbc.query(CONVERSATIONS + " AND strpos(lower(u.display_name), lower(:name)) > 0"
                + before + " ORDER BY c.updated_at DESC, c.id DESC LIMIT :limit",
                params, (rs, row) -> conversation(rs));
        return page(rows, limit, scope, item -> new CursorPosition(item.updatedAt(), item.id()));
    }

    @Transactional(readOnly = true)
    Conversation conversation(UUID actor, UUID conversation) {
        return jdbc.query(CONVERSATIONS + " AND c.id = :conversation",
                Map.of("actor", actor, "conversation", conversation), (rs, row) -> conversation(rs))
                .stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Direct conversation not found."));
    }

    @Transactional
    Conversation start(UUID actor, UUID peer) {
        if (peer == null || actor.equals(peer)) {
            throw ApiProblemException.validationFailed("Select another active user.");
        }
        var pair = Map.of("actor", actor, "peer", peer);
        requireActivePair(pair);
        var inserted = jdbc.query("""
                INSERT INTO direct_conversations (low_user_id, high_user_id)
                VALUES (LEAST(:actor, :peer), GREATEST(:actor, :peer))
                ON CONFLICT (low_user_id, high_user_id) DO NOTHING
                RETURNING id
                """, pair, (rs, row) -> id(rs, "id"));
        if (!inserted.isEmpty()) {
            takeWriteBudget(actor);
            events.publishEvent(new DirectMessageRealtime.Changed(inserted.getFirst(), actor, peer));
        }
        // A competing start may have committed while INSERT waited. The next
        // statement sees the canonical row at READ COMMITTED.
        return jdbc.query(CONVERSATIONS + " AND u.id = :peer", pair, (rs, row) -> conversation(rs)).getFirst();
    }

    @Transactional(readOnly = true)
    CursorPage<Message> messages(UUID actor, UUID conversation, String cursor, Integer requestedLimit) {
        participant(actor, conversation, false);
        String scope = "dm-history:" + actor + ":" + conversation;
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        var params = new MapSqlParameterSource("conversation", conversation).addValue("limit", limit + 1);
        String before = "";
        if (cursor != null) {
            params.addValue("position", cursors.decode(cursor, scope).sortValue().getEpochSecond());
            before = " AND position < :position";
        }
        var rows = jdbc.query("""
                SELECT id, sender_id, body, created_at, position
                FROM direct_messages WHERE conversation_id = :conversation
                """ + before + " ORDER BY position DESC LIMIT :limit", params,
                (rs, row) -> new HistoryRow(new Message(id(rs, "id"), id(rs, "sender_id"),
                        rs.getString("body"), instant(rs, "created_at")), rs.getLong("position")));
        var result = page(rows, limit, scope,
                row -> new CursorPosition(Instant.ofEpochSecond(row.position()), row.message().id()));
        return new CursorPage<>(result.items().stream().map(HistoryRow::message).toList(), result.nextCursor());
    }

    IdempotencyResult send(UUID actor, UUID conversation, String key, String body) {
        if (body == null || body.isBlank() || body.length() > 4000 || body.indexOf(0) >= 0) {
            throw ApiProblemException.validationFailed("Enter a message of 1 to 4000 characters.");
        }
        return idempotency.execute(actor, "direct-message:" + conversation, key, Map.of("body", body), () -> {
            UUID peer = participant(actor, conversation, false);
            requireActivePair(Map.of("actor", actor, "peer", peer));
            participant(actor, conversation, true);
            takeWriteBudget(actor);
            var sent = jdbc.query("""
                    INSERT INTO direct_messages (conversation_id, sender_id, body)
                    VALUES (:conversation, :actor, :body) RETURNING id, created_at
                    """, Map.of("conversation", conversation, "actor", actor, "body", body),
                    (rs, row) -> new Sent(id(rs, "id"), instant(rs, "created_at"))).getFirst();
            jdbc.update("""
                    UPDATE direct_conversations SET updated_at = GREATEST(updated_at, clock_timestamp())
                    WHERE id = :conversation
                    """, Map.of("conversation", conversation));
            events.publishEvent(new DirectMessageRealtime.Changed(conversation, actor, peer));
            // Replay stores only acknowledgement metadata alongside the request digest.
            return new IdempotencyResponse(201, json.valueToTree(sent));
        });
    }

    @Transactional
    void read(UUID actor, UUID conversation, UUID message) {
        participant(actor, conversation, true);
        if (message == null) throw ApiProblemException.validationFailed("A message ID is required.");
        var params = Map.of("actor", actor, "conversation", conversation, "message", message);
        var positions = jdbc.query("""
                SELECT position FROM direct_messages WHERE conversation_id = :conversation AND id = :message
                """, params, (rs, row) -> rs.getLong("position"));
        if (positions.isEmpty()) throw ApiProblemException.notFound("Direct message not found.");
        int changed = jdbc.update("""
                UPDATE direct_conversations c
                SET low_read_through = CASE WHEN low_user_id = :actor THEN :position ELSE low_read_through END,
                    high_read_through = CASE WHEN high_user_id = :actor THEN :position ELSE high_read_through END
                WHERE id = :conversation AND (%s) < :position
                """.formatted(READ_POSITION),
                Map.of("actor", actor, "conversation", conversation, "position", positions.getFirst()));
        if (changed != 0) events.publishEvent(new DirectMessageRealtime.Changed(conversation, actor, null));
    }

    private UUID participant(UUID actor, UUID conversation, boolean lock) {
        return jdbc.query("""
                SELECT CASE WHEN c.low_user_id = :actor THEN c.high_user_id ELSE c.low_user_id END AS peer
                FROM direct_conversations c WHERE c.id = :conversation AND
                """ + MEMBERSHIP + (lock ? " FOR UPDATE" : ""), Map.of("actor", actor, "conversation", conversation),
                (rs, row) -> id(rs, "peer")).stream().findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Direct conversation not found."));
    }

    private void requireActivePair(Map<String, UUID> users) {
        var active = jdbc.query("SELECT id FROM users WHERE id IN (:actor, :peer) AND " + ACTIVE
                + " ORDER BY id FOR SHARE", users, (rs, row) -> id(rs, "id"));
        if (!active.contains(users.get("actor"))) throw ApiProblemException.authenticationRequired();
        if (!active.contains(users.get("peer"))) throw ApiProblemException.notFound("Active recipient not found.");
    }

    private void takeWriteBudget(UUID actor) {
        int changed = jdbc.update("""
                INSERT INTO direct_message_write_budgets AS budget (user_id, minute_start, writes)
                VALUES (:actor, date_trunc('minute', statement_timestamp()), 1)
                ON CONFLICT (user_id) DO UPDATE
                SET writes = CASE WHEN budget.minute_start = EXCLUDED.minute_start THEN budget.writes + 1 ELSE 1 END,
                    minute_start = EXCLUDED.minute_start
                WHERE budget.minute_start < EXCLUDED.minute_start
                   OR (budget.minute_start = EXCLUDED.minute_start AND budget.writes < 60)
                """, Map.of("actor", actor));
        if (changed == 0) throw ApiProblemException.rateLimited("Too many direct message writes. Try again shortly.");
    }

    private <T> CursorPage<T> page(List<T> rows, int limit, String scope, Function<T, CursorPosition> position) {
        if (rows.size() <= limit) return new CursorPage<>(rows, null);
        var items = rows.subList(0, limit);
        return new CursorPage<>(items, cursors.encode(position.apply(items.getLast()), scope));
    }

    private static String searchTerm(String search) {
        String name = search == null ? "" : search.strip();
        if (name.length() > 100 || name.indexOf(0) >= 0) {
            throw ApiProblemException.validationFailed("Invalid display name search.");
        }
        return name;
    }

    private static Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(id(rs, "id"), new Recipient(id(rs, "peer_id"), rs.getString("display_name")),
                rs.getBoolean("peer_active"), instant(rs, "updated_at"), rs.getLong("unread"));
    }

    private static UUID id(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    record Recipient(UUID id, String displayName) { }
    record Conversation(UUID id, Recipient recipient, boolean recipientActive, Instant updatedAt, long unreadCount) { }
    record Message(UUID id, UUID senderId, String body, Instant createdAt) { }
    record Sent(UUID messageId, Instant createdAt) { }
    private record HistoryRow(Message message, long position) { }
}
