package com.unifiedsupportinbox.workflow;

import static com.unifiedsupportinbox.cases.CaseStatus.*;
import static com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Action.*;
import static com.unifiedsupportinbox.workflow.CaseTransitionPolicy.Effect.*;
import static org.assertj.core.api.Assertions.*;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class CaseTransitionPolicyTests {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Actor USER = actor(OWNER, UserRole.USER, Set.of(), false);
    private static final Actor ADMIN = actor(OWNER, UserRole.ADMIN, Set.of("reassign_cases", "force_resolve"), false);
    private static final Actor TARGET = actor(OTHER, UserRole.USER, Set.of(), false);
    private static final Input VALID = new Input(TARGET, 0, 1, false, true, Duration.ofHours(24));
    private final CaseTransitionPolicy policy = new CaseTransitionPolicy();

    static Stream<Arguments> matrix() {
        // Independent frozen matrix; no implementation helpers are used for expectations.
        return Stream.of(
                Arguments.of(NEW, Set.of(CLAIM, IGNORE, ASSIGN, FORCE_RESOLVE, SNOOZE, MARK_READ)),
                Arguments.of(PARTIALLY_IGNORED, Set.of(CLAIM, IGNORE, ASSIGN, FORCE_RESOLVE, SNOOZE, MARK_READ)),
                Arguments.of(VERIFICATION, Set.of(REPLY, ASK_CUSTOMER, RESOLVE, REASSIGN, UNASSIGN, FORCE_RESOLVE, SNOOZE, MARK_READ)),
                Arguments.of(WAITING_FOR_CUSTOMER, Set.of(FORCE_RESOLVE, SNOOZE, MARK_READ)),
                Arguments.of(IGNORED, Set.of(MARK_READ)),
                Arguments.of(RESOLVED, Set.of(MARK_READ)))
                .flatMap(row -> Stream.of(Action.values()).map(action ->
                        Arguments.of(row.get()[0], action, ((Set<?>) row.get()[1]).contains(action))));
    }

    @ParameterizedTest(name = "{0} / {1}: allowed={2}")
    @MethodSource("matrix")
    void validatesEveryAllowedAndForbiddenAction(CaseStatus status, Action action, boolean allowed) {
        State state = state(status);
        assertThat(policy.availableActions(state, ADMIN).contains(action)).isEqualTo(allowed);
        if (allowed) {
            Decision result = policy.decide(state, ADMIN, action, VALID);
            assertThat(result.state()).isNotNull();
            switch (action) {
                case CLAIM -> assertThat(result.state()).isEqualTo(new State(VERIFICATION, OWNER, null));
                case ASSIGN, REASSIGN -> assertThat(result.state()).isEqualTo(new State(VERIFICATION, OTHER, null));
                case RESOLVE, FORCE_RESOLVE -> assertThat(result.state()).isEqualTo(new State(RESOLVED, state.ownerId(), null));
                case UNASSIGN -> assertThat(result.state()).isEqualTo(new State(NEW, null, null));
                case IGNORE -> assertThat(result.state()).isEqualTo(new State(PARTIALLY_IGNORED, null, null));
                default -> assertThat(result.state()).isEqualTo(state);
            }
        } else {
            assertProblem(() -> policy.decide(state, ADMIN, action, VALID), ApiProblemCode.CONFLICT);
        }
    }

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    void inactiveActorsHaveNoActions(CaseStatus status) {
        Actor inactive = new Actor(OWNER, false, UserRole.ADMIN, ADMIN.permissions(), false);
        assertThat(policy.availableActions(state(status), inactive)).isEmpty();
        for (Action action : Action.values()) {
            assertProblem(() -> policy.decide(state(status), inactive, action, VALID), ApiProblemCode.ACCESS_DENIED);
        }
    }

    @Test
    void adminPermissionsRequireBothRoleAndPermission() {
        for (Actor actor : Set.of(USER, actor(OWNER, UserRole.ADMIN, Set.of(), false),
                actor(OWNER, UserRole.USER, ADMIN.permissions(), false))) {
            for (Action action : Set.of(ASSIGN, REASSIGN, UNASSIGN, FORCE_RESOLVE)) {
                State state = state(action == ASSIGN ? NEW : VERIFICATION);
                assertThat(policy.availableActions(state, actor)).doesNotContain(action);
                assertProblem(() -> policy.decide(state, actor, action, VALID), ApiProblemCode.ACCESS_DENIED);
            }
        }
    }

    @Test
    void anotherUserOrAdminCannotReplyAskOrNormallyResolve() {
        for (Actor actor : Set.of(TARGET, actor(OTHER, UserRole.ADMIN, ADMIN.permissions(), false))) {
            for (Action action : Set.of(REPLY, ASK_CUSTOMER, RESOLVE)) {
                assertThat(policy.availableActions(state(VERIFICATION), actor)).doesNotContain(action);
                assertProblem(() -> policy.decide(state(VERIFICATION), actor, action, VALID), ApiProblemCode.CONFLICT);
            }
        }
    }

    @Test
    void lifetimeVotersCannotClaimReplyOrAskEvenAfterVoteReset() {
        Actor formerVoter = actor(OWNER, UserRole.USER, Set.of(), true);
        for (CaseStatus status : Set.of(NEW, PARTIALLY_IGNORED)) {
            assertProblem(() -> policy.decide(state(status), formerVoter, CLAIM, VALID), ApiProblemCode.CONFLICT);
            // A prior reset permits a new Ignore vote, but not Claim/Reply.
            assertThat(policy.decide(state(status), formerVoter, IGNORE, VALID).effects()).contains(RECORD_IGNORE_VOTE);
        }
        for (Action action : Set.of(REPLY, ASK_CUSTOMER)) {
            assertProblem(() -> policy.decide(state(VERIFICATION), formerVoter, action, VALID), ApiProblemCode.CONFLICT);
        }
    }

    @Test
    void assignmentRejectsInactiveMissingAndLifetimeVoterTargets() {
        for (Actor target : new Actor[] {null, new Actor(OTHER, false, UserRole.USER, Set.of(), false),
                actor(OTHER, UserRole.USER, Set.of(), true)}) {
            Input input = new Input(target, 0, 1, false, true, null);
            assertProblem(() -> policy.decide(state(NEW), ADMIN, ASSIGN, input), ApiProblemCode.CONFLICT);
            assertProblem(() -> policy.decide(state(VERIFICATION), ADMIN, REASSIGN, input), ApiProblemCode.CONFLICT);
        }
    }

    @Test
    void claimDeactivatesVotesAndClearsSnoozes() {
        assertThat(policy.decide(state(PARTIALLY_IGNORED), USER, CLAIM, VALID).effects())
                .containsExactlyInAnyOrder(DEACTIVATE_IGNORE_VOTES, CLEAR_SNOOZES, AUDIT, UPDATE_SLA);
    }

    @Test
    void ignoreUsesFixedThresholdSnapshotAndDuplicateDoesNotAddPoints() {
        for (Input input : new Input[] {new Input(null, 1, 1, false, false, null),
                new Input(null, 0, 2, false, false, null)}) {
            Decision result = policy.decide(state(PARTIALLY_IGNORED), USER, IGNORE, input);
            assertThat(result.state()).isEqualTo(new State(IGNORED, null, null));
            assertThat(result.effects()).contains(RECORD_IGNORE_VOTE, CLEAR_SNOOZES, AUDIT, UPDATE_SLA);
        }
        Decision duplicate = policy.decide(state(PARTIALLY_IGNORED), USER, IGNORE,
                new Input(null, 1, 2, true, false, null));
        assertThat(duplicate.state()).isEqualTo(state(PARTIALLY_IGNORED));
        assertThat(duplicate.effects()).isEmpty();
        for (Input input : new Input[] {new Input(null, -1, 1, false, false, null),
                new Input(null, 2, 1, false, false, null), new Input(null, 0, 0, false, false, null),
                new Input(null, 0, 3, false, false, null)}) {
            assertProblem(() -> policy.decide(state(NEW), USER, IGNORE, input), ApiProblemCode.VALIDATION_FAILED);
        }
    }

    @Test
    void asksWaitForProviderSuccessAndDefaultToTwentyFourHours() {
        State owned = state(VERIFICATION);
        assertThat(policy.decide(owned, USER, ASK_CUSTOMER, VALID))
                .isEqualTo(new Decision(owned, Set.of(QUEUE_ASK_MESSAGE)));
        assertThat(policy.askNotSent(owned)).isEqualTo(new Decision(owned, Set.of()));
        assertThat(policy.askSent(owned, OWNER, null, NOW).state())
                .isEqualTo(new State(WAITING_FOR_CUSTOMER, null, NOW.plus(Duration.ofHours(24))));
        assertThat(policy.askSent(owned, OWNER, Duration.ofHours(1), NOW).state().waitingUntil())
                .isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(policy.askSent(owned, OWNER, Duration.ofDays(30), NOW).state().waitingUntil())
                .isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertProblem(() -> policy.askSent(owned, OTHER, null, NOW), ApiProblemCode.CONFLICT);
        for (CaseStatus status : CaseStatus.values()) {
            if (status != VERIFICATION) assertProblem(() -> policy.askSent(state(status), OWNER, null, NOW), ApiProblemCode.CONFLICT);
        }
    }

    @Test
    void askAndReplyRequireMessageAndAskDurationIsBounded() {
        for (Action action : Set.of(REPLY, ASK_CUSTOMER)) {
            assertProblem(() -> policy.decide(state(VERIFICATION), USER, action, Input.empty()), ApiProblemCode.VALIDATION_FAILED);
        }
        for (Duration duration : new Duration[] {Duration.ZERO, Duration.ofMinutes(59), Duration.ofDays(30).plusSeconds(1)}) {
            Input input = new Input(null, 0, 1, false, true, duration);
            assertProblem(() -> policy.decide(state(VERIFICATION), USER, ASK_CUSTOMER, input), ApiProblemCode.VALIDATION_FAILED);
            assertProblem(() -> policy.askSent(state(VERIFICATION), OWNER, duration, NOW), ApiProblemCode.VALIDATION_FAILED);
        }
    }

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    void customerMessagesNeverReopenTerminalCases(CaseStatus status) {
        Decision result = policy.customerMessage(state(status));
        switch (status) {
            case IGNORED, RESOLVED -> {
                assertThat(result.state()).isEqualTo(state(status));
                assertThat(result.effects()).containsExactly(CREATE_LINKED_CASE);
            }
            case PARTIALLY_IGNORED -> {
                assertThat(result.state()).isEqualTo(state(NEW));
                assertThat(result.effects()).contains(DEACTIVATE_IGNORE_VOTES, CLEAR_SNOOZES);
            }
            case WAITING_FOR_CUSTOMER -> assertThat(result.state()).isEqualTo(state(NEW));
            default -> assertThat(result.state()).isEqualTo(state(status));
        }
    }

    @Test
    void timeoutRequiresDueWaitingCaseAndNeverQueuesFollowup() {
        assertProblem(() -> policy.waitingTimeout(state(WAITING_FOR_CUSTOMER), NOW.minusNanos(1)), ApiProblemCode.CONFLICT);
        Decision result = policy.waitingTimeout(state(WAITING_FOR_CUSTOMER), NOW);
        assertThat(result.state()).isEqualTo(state(NEW));
        assertThat(result.effects()).containsExactlyInAnyOrder(AUDIT, UPDATE_SLA);
        for (CaseStatus status : CaseStatus.values()) {
            if (status != WAITING_FOR_CUSTOMER) assertProblem(() -> policy.waitingTimeout(state(status), NOW), ApiProblemCode.CONFLICT);
        }
    }

    @Test
    void snoozeIsPersonalBoundedAndDoesNotChangeWorkflow() {
        for (Duration duration : new Duration[] {Duration.ofMinutes(5), Duration.ofDays(30)}) {
            Decision result = policy.decide(state(VERIFICATION), TARGET, SNOOZE,
                    new Input(null, 0, 1, false, false, duration));
            assertThat(result.state()).isEqualTo(state(VERIFICATION));
            assertThat(result.effects()).containsExactlyInAnyOrder(MARK_ACTOR_READ, SCHEDULE_PERSONAL_REMINDER);
        }
        for (Duration duration : new Duration[] {null, Duration.ofMinutes(5).minusNanos(1), Duration.ofDays(30).plusNanos(1)}) {
            assertProblem(() -> policy.decide(state(NEW), USER, SNOOZE,
                    new Input(null, 0, 1, false, false, duration)), ApiProblemCode.VALIDATION_FAILED);
        }
    }

    @Test
    void rejectsInvalidOwnershipAndWaitingSnapshots() {
        assertThatIllegalArgumentException().isThrownBy(() -> new State(VERIFICATION, null, null));
        for (CaseStatus status : Set.of(NEW, PARTIALLY_IGNORED, WAITING_FOR_CUSTOMER, IGNORED)) {
            assertThatIllegalArgumentException().isThrownBy(() -> new State(status, OWNER, status == WAITING_FOR_CUSTOMER ? NOW : null));
        }
        for (CaseStatus status : CaseStatus.values()) {
            assertThatIllegalArgumentException().isThrownBy(() -> new State(status,
                    status == VERIFICATION ? OWNER : null, status == WAITING_FOR_CUSTOMER ? null : NOW));
        }
        assertThat(new State(RESOLVED, null, null).terminal()).isTrue();
        assertThat(new State(RESOLVED, OWNER, null).terminal()).isTrue();
    }

    private static State state(CaseStatus status) {
        return new State(status, status == VERIFICATION || status == RESOLVED ? OWNER : null,
                status == WAITING_FOR_CUSTOMER ? NOW : null);
    }
    private static Actor actor(UUID id, UserRole role, Set<String> permissions, boolean voter) {
        return new Actor(id, true, role, permissions, voter);
    }
    private static void assertProblem(Runnable operation, ApiProblemCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
