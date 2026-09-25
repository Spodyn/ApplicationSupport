package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Action;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Actor;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Decision;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Input;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.State;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal application boundary for dedicated workflow commands, never an HTTP
 * generic status setter. Command handlers load actor/vote facts after the Case
 * lock and persist every required effect (audit/outbox/etc.) in the callback.
 * Callbacks must not perform provider/network I/O. Any callback failure rolls
 * back the state update and all effects together.
 */
@Service
public class CaseWorkflowTransactions {
    private final JdbcTemplate jdbc;
    private final CaseTransitionPolicy policy;
    private final AuditEventStore auditEvents;

    public CaseWorkflowTransactions(JdbcTemplate jdbc, CaseTransitionPolicy policy, AuditEventStore auditEvents) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.auditEvents = auditEvents;
    }

    public record Command(Actor actor, Action action, Input input) {}
    public record Result(State state, long version, Set<Action> availableActions) {}

    @Transactional
    public Result execute(UUID caseId, long expectedVersion, Function<State, Command> loadCommand,
                          Consumer<Decision> persistEffects) {
        Objects.requireNonNull(loadCommand, "loadCommand");
        Objects.requireNonNull(persistEffects, "persistEffects");
        var rows = jdbc.query("""
                SELECT status, owner_user_id, waiting_until, version
                FROM cases WHERE id = ? FOR UPDATE
                """, (rs, row) -> {
                    OffsetDateTime waiting = rs.getObject("waiting_until", OffsetDateTime.class);
                    return new Snapshot(new State(CaseStatus.valueOf(rs.getString("status")),
                            rs.getObject("owner_user_id", UUID.class), waiting == null ? null : waiting.toInstant()),
                            rs.getLong("version"));
                }, caseId);
        if (rows.isEmpty()) throw ApiProblemException.notFound("Case was not found.");
        Snapshot before = rows.getFirst();
        if (before.version() != expectedVersion) throw ApiProblemException.conflict("The Case has changed. Refresh before retrying.");
        Command command = Objects.requireNonNull(loadCommand.apply(before.state()), "command");
        Decision decision = policy.decide(before.state(), command.actor(), command.action(), command.input());
        State after = decision.state();
        long version = before.version();
        if (!before.state().equals(after)) {
            int changed = jdbc.update("""
                    UPDATE cases SET
                        status = ?,
                        claimed_at = CASE WHEN ?::uuid IS NOT NULL AND owner_user_id IS DISTINCT FROM ?::uuid
                                          THEN statement_timestamp() ELSE claimed_at END,
                        owner_user_id = ?, waiting_until = ?,
                        resolved_at = CASE WHEN ? = 'RESOLVED' THEN statement_timestamp() ELSE NULL END,
                        ignored_at = CASE WHEN ? = 'IGNORED' THEN statement_timestamp() ELSE NULL END,
                        updated_at = statement_timestamp(), last_activity_at = statement_timestamp(),
                        version = version + 1
                    WHERE id = ? AND version = ?
                    """, after.status().name(), after.ownerId(), after.ownerId(), after.ownerId(),
                    after.waitingUntil() == null ? null : OffsetDateTime.ofInstant(after.waitingUntil(), ZoneOffset.UTC),
                    after.status().name(), after.status().name(), caseId, before.version());
            if (changed != 1) throw ApiProblemException.conflict("The Case has changed. Refresh before retrying.");
            version++;
        }
        persistEffects.accept(decision);
        if (!before.state().equals(after) || !decision.effects().isEmpty()) {
            auditEvents.append(
                    actorType(command.actor()),
                    command.actor().id(),
                    "CASE_" + command.action().name(),
                    "CASE",
                    caseId,
                    caseId,
                    Map.of(
                            "action", command.action().name(),
                            "previous", stateMetadata(before.state()),
                            "current", stateMetadata(after),
                            "version", version));
        }
        Actor actor = command.actor();
        if (decision.effects().contains(CaseTransitionPolicy.Effect.RECORD_IGNORE_VOTE)) {
            actor = new Actor(actor.id(), actor.active(), actor.role(), actor.permissions(), true);
        }
        return new Result(after, version, policy.availableActions(after, actor));
    }

    private record Snapshot(State state, long version) {}

    private static AuditActorType actorType(Actor actor) {
        return actor.role() == com.unifiedsupportinbox.identity.UserRole.ADMIN
                ? AuditActorType.ADMIN : AuditActorType.USER;
    }

    private static Map<String, Object> stateMetadata(State state) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", state.status().name());
        if (state.ownerId() != null) metadata.put("ownerUserId", state.ownerId().toString());
        if (state.waitingUntil() != null) metadata.put("waitingUntil", state.waitingUntil().toString());
        return Map.copyOf(metadata);
    }
}
