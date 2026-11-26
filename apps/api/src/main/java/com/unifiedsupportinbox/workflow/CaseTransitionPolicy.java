package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Authoritative v1 workflow decisions. Facts must be loaded by a command in its
 * database transaction, never accepted from browser/provider payloads. A decision
 * describes required effects; it does not perform I/O or commit a partial command.
 */
@Component
public final class CaseTransitionPolicy {
    public enum Action { CLAIM, REPLY, IGNORE, RESOLVE, ASSIGN, REASSIGN, UNASSIGN, FORCE_RESOLVE, SNOOZE, MARK_READ }
    public enum Effect { DEACTIVATE_IGNORE_VOTES, CLEAR_SNOOZES, RECORD_IGNORE_VOTE, QUEUE_SUPPORT_MESSAGE,
        AUDIT, UPDATE_SLA, MARK_ACTOR_READ, SCHEDULE_PERSONAL_REMINDER, CREATE_LINKED_CASE }

    public record State(CaseStatus status, UUID ownerId, Instant waitingUntil) {
        public State {
            Objects.requireNonNull(status, "status");
            if (status == CaseStatus.VERIFICATION && ownerId == null
                    || status != CaseStatus.VERIFICATION && status != CaseStatus.RESOLVED && status != CaseStatus.WAITING_FOR_CUSTOMER && ownerId != null
                    || (status == CaseStatus.WAITING_FOR_CUSTOMER) != (waitingUntil != null)) {
                throw new IllegalArgumentException("Invalid Case ownership or waiting state.");
            }
        }
        public boolean terminal() { return status == CaseStatus.IGNORED || status == CaseStatus.RESOLVED; }
    }

    /** Server-loaded authorization and immutable lifetime vote history for one Case. */
    public record Actor(UUID id, boolean active, UserRole role, Set<String> permissions,
                        boolean lifetimeIgnoreVoter) {
        public Actor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(role, "role");
            permissions = Set.copyOf(permissions);
        }
        boolean admin(String permission) { return role == UserRole.ADMIN && permissions.contains(permission); }
    }

    /** Optional command inputs. Vote values are server-calculated, not request values. */
    public record Input(Actor target, int activeIgnorePoints, int voteWeight, boolean alreadyVoted,
                        boolean hasOutgoingMessage, Duration duration) {
        public static Input empty() { return new Input(null, 0, 1, false, false, null); }
    }

    public record Decision(State state, Set<Effect> effects) {
        public Decision { effects = Set.copyOf(effects); }
    }

    /** Eligibility projection and execution use this same gate. Input validation follows at execution. */
    public Set<Action> availableActions(State state, Actor actor) {
        EnumSet<Action> result = EnumSet.noneOf(Action.class);
        if (!actor.active()) return Set.of();
        for (Action action : Action.values()) if (allowed(state, actor, action)) result.add(action);
        return Set.copyOf(result);
    }

    public Decision decide(State state, Actor actor, Action action, Input input) {
        Objects.requireNonNull(input, "input");
        if (!actor.active()) throw ApiProblemException.accessDenied();
        if (!allowed(state, actor, action)) {
            if (action == Action.ASSIGN || action == Action.REASSIGN || action == Action.UNASSIGN) {
                if (!actor.admin("reassign_cases")) throw ApiProblemException.accessDenied();
            }
            if (action == Action.FORCE_RESOLVE && !actor.admin("force_resolve")) throw ApiProblemException.accessDenied();
            throw ApiProblemException.conflict("The action is not available for this Case and actor.");
        }
        return switch (action) {
            case CLAIM -> decision(CaseStatus.VERIFICATION, actor.id(), null,
                    Effect.DEACTIVATE_IGNORE_VOTES, Effect.CLEAR_SNOOZES, Effect.AUDIT, Effect.UPDATE_SLA);
            case REPLY -> {
                requireMessage(input);
                yield decision(state, Effect.QUEUE_SUPPORT_MESSAGE);
            }
            case IGNORE -> {
                if (input.alreadyVoted()) yield decision(state);
                if (input.activeIgnorePoints() < 0 || input.activeIgnorePoints() >= 2
                        || input.voteWeight() < 1 || input.voteWeight() > 2) {
                    throw ApiProblemException.validationFailed("Invalid effective Ignore vote.");
                }
                boolean terminal = input.activeIgnorePoints() + input.voteWeight() >= 2;
                yield terminal
                        ? decision(CaseStatus.IGNORED, null, null, Effect.RECORD_IGNORE_VOTE,
                                Effect.CLEAR_SNOOZES, Effect.AUDIT, Effect.UPDATE_SLA)
                        : decision(CaseStatus.PARTIALLY_IGNORED, null, null, Effect.RECORD_IGNORE_VOTE, Effect.AUDIT);
            }
            case RESOLVE, FORCE_RESOLVE -> decision(CaseStatus.RESOLVED, state.ownerId(), null,
                    Effect.CLEAR_SNOOZES, Effect.AUDIT, Effect.UPDATE_SLA);
            case ASSIGN, REASSIGN -> {
                Actor target = input.target();
                if (target == null || !target.active() || target.lifetimeIgnoreVoter()) {
                    throw ApiProblemException.conflict("Assignment target is not eligible for this Case.");
                }
                yield decision(CaseStatus.VERIFICATION, target.id(), null,
                        Effect.DEACTIVATE_IGNORE_VOTES, Effect.AUDIT, Effect.UPDATE_SLA);
            }
            case UNASSIGN -> decision(CaseStatus.NEW, null, null, Effect.AUDIT, Effect.UPDATE_SLA);
            case SNOOZE -> {
                requireDuration(input.duration(), Duration.ofMinutes(5), Duration.ofDays(30));
                yield decision(state, Effect.MARK_ACTOR_READ, Effect.SCHEDULE_PERSONAL_REMINDER);
            }
            case MARK_READ -> decision(state, Effect.MARK_ACTOR_READ);
        };
    }

    public Decision customerMessage(State state) {
        if (state.terminal()) return decision(state, Effect.CREATE_LINKED_CASE);
        return switch (state.status()) {
            case PARTIALLY_IGNORED -> decision(CaseStatus.NEW, null, null,
                    Effect.DEACTIVATE_IGNORE_VOTES, Effect.CLEAR_SNOOZES, Effect.AUDIT, Effect.UPDATE_SLA);
            case WAITING_FOR_CUSTOMER -> decision(state.ownerId() == null ? CaseStatus.NEW : CaseStatus.VERIFICATION,
                    state.ownerId(), null, Effect.CLEAR_SNOOZES, Effect.AUDIT, Effect.UPDATE_SLA);
            default -> decision(state, Effect.CLEAR_SNOOZES);
        };
    }

    public Decision waitingTimeout(State state, Instant now) {
        if (state.status() != CaseStatus.WAITING_FOR_CUSTOMER || now.isBefore(state.waitingUntil())) {
            throw ApiProblemException.conflict("The Case waiting deadline is not due.");
        }
        return decision(state.ownerId() == null ? CaseStatus.NEW : CaseStatus.VERIFICATION,
                state.ownerId(), null, Effect.AUDIT, Effect.UPDATE_SLA);
    }

    private boolean allowed(State state, Actor actor, Action action) {
        boolean unclaimed = state.status() == CaseStatus.NEW || state.status() == CaseStatus.PARTIALLY_IGNORED;
        boolean owner = state.status().acceptsOwnerWork() && actor.id().equals(state.ownerId());
        return switch (action) {
            case CLAIM -> (unclaimed || (state.status() == CaseStatus.WAITING_FOR_CUSTOMER && state.ownerId() == null))
                    && !actor.lifetimeIgnoreVoter();
            case REPLY -> owner && !actor.lifetimeIgnoreVoter();
            case IGNORE -> unclaimed;
            case RESOLVE -> owner;
            case ASSIGN -> unclaimed && actor.admin("reassign_cases");
            case REASSIGN, UNASSIGN -> state.status() == CaseStatus.VERIFICATION && actor.admin("reassign_cases");
            case FORCE_RESOLVE -> !state.terminal() && actor.admin("force_resolve");
            case SNOOZE -> !state.terminal();
            case MARK_READ -> true;
        };
    }

    private static void requireDuration(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw ApiProblemException.validationFailed("Duration is outside the permitted range.");
        }
    }

    private static void requireMessage(Input input) {
        if (!input.hasOutgoingMessage()) throw ApiProblemException.validationFailed("An outgoing support message is required.");
    }

    private static Decision decision(CaseStatus status, UUID owner, Instant waiting, Effect... effects) {
        return decision(new State(status, owner, waiting), effects);
    }

    private static Decision decision(State state, Effect... effects) {
        return new Decision(state, Set.of(effects));
    }
}
