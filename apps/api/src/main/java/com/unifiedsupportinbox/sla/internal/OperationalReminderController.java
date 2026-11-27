package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications/sla-reminders")
class OperationalReminderController {
    private final JdbcTemplate jdbc;

    OperationalReminderController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @Transactional(readOnly = true)
    ReminderPage list(Authentication authentication) {
        UUID user = eligibleUser(authentication);
        var reminders = jdbc.query("""
                SELECT d.id, c.id AS case_id, c.reference, c.provider, d.kind, d.due_at, d.reminded_at, r.read_at,
                       COUNT(*) FILTER (WHERE r.read_at IS NULL) OVER () AS unread_count
                FROM case_deadline_reminder_recipients r
                JOIN case_operational_deadlines d ON d.id = r.deadline_id
                JOIN cases c ON c.id = d.case_id
                WHERE r.user_id = ?
                ORDER BY d.reminded_at DESC, d.id DESC LIMIT 100
                """, (rs, row) -> new CountedReminder(new Reminder(rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getString("reference"), rs.getString("provider"),
                rs.getString("kind"), rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                rs.getObject("reminded_at", OffsetDateTime.class).toInstant(), rs.getObject("read_at") == null),
                rs.getLong("unread_count")), user);
        return new ReminderPage(reminders.stream().map(CountedReminder::reminder).toList(),
                reminders.isEmpty() ? 0 : reminders.getFirst().unreadCount());
    }

    @PostMapping("/read")
    @Transactional
    ReadResult markRead(Authentication authentication, @RequestBody ReadRequest request) {
        UUID user = eligibleUser(authentication);
        int updated = jdbc.update("""
                UPDATE case_deadline_reminder_recipients SET read_at = statement_timestamp()
                WHERE user_id = ? AND read_at IS NULL AND (?::uuid IS NULL OR deadline_id = ?)
                """, user, request.deadlineId(), request.deadlineId());
        return new ReadResult(updated);
    }

    private UUID eligibleUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        UUID id;
        try { id = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException failure) { throw ApiProblemException.authenticationRequired(); }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND active AND role IN ('USER', 'ADMIN')
                    AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                    AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP))
                """, Boolean.class, id))) throw ApiProblemException.accessDenied();
        return id;
    }

    record Reminder(UUID id, UUID caseId, String reference, String provider, String kind,
                    Instant dueAt, Instant remindedAt, boolean unread) {}
    record ReminderPage(List<Reminder> items, long unreadCount) {}
    record ReadRequest(UUID deadlineId) {}
    record ReadResult(int updatedCount) {}
    private record CountedReminder(Reminder reminder, long unreadCount) {}
}
