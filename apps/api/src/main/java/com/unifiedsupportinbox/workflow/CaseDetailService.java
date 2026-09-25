package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read model for the Case header.  This deliberately has no join to messages:
 * message history is paged through its own endpoint.
 */
@Service
class CaseDetailService {

    private final JdbcTemplate jdbc;
    private final CaseTransitionPolicy policy;

    CaseDetailService(JdbcTemplate jdbc, CaseTransitionPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    CaseDetail detail(UUID caseId, UUID userId) {
        Actor actor = actor(caseId, userId);
        return jdbc.query("""
                SELECT c.id, c.reference, c.status, c.owner_user_id, owner.display_name AS owner_display_name,
                       c.claimed_at, c.waiting_until, c.resolved_at, c.ignored_at, c.resolution_category,
                       c.related_case_id, related.reference AS related_case_reference,
                       c.created_at, c.updated_at, c.last_activity_at, c.version,
                       customer.id AS customer_id, customer.name AS customer_name, customer.external_ref AS customer_external_ref,
                       channel.id AS channel_id, channel.name AS channel_name, channel.external_channel_id, channel.grouping_strategy,
                       integration.id AS integration_id, integration.provider, integration.display_name AS integration_display_name,
                       integration.workspace_external_id, integration.workspace_name,
                       COALESCE(votes.points, 0) AS ignore_score,
                       reads.last_read_message_id, reads.last_read_at, snoozes.until_at AS snoozed_until,
                       sla.policy_id, sla.first_response_started_at, sla.first_response_due_at, sla.first_response_completed_at,
                       sla.unclaimed_started_at, sla.unclaimed_warning_at, sla.unclaimed_breach_at,
                       sla.in_progress_started_at, sla.in_progress_warning_at, sla.in_progress_breach_at,
                       sla.paused_at, sla.total_paused_seconds, sla.state AS sla_state, sla.unclaimed_completed_at, sla.unclaimed_outcome
                FROM cases c
                JOIN customers customer ON customer.id = c.customer_id
                JOIN channels channel ON channel.id = c.channel_id
                JOIN integrations integration ON integration.id = c.integration_id
                LEFT JOIN users owner ON owner.id = c.owner_user_id
                LEFT JOIN cases related ON related.id = c.related_case_id
                LEFT JOIN case_read_states reads ON reads.case_id = c.id AND reads.user_id = ?
                LEFT JOIN case_snoozes snoozes ON snoozes.case_id = c.id AND snoozes.user_id = ?
                LEFT JOIN case_sla sla ON sla.case_id = c.id
                LEFT JOIN LATERAL (
                    SELECT SUM(weight)::int AS points FROM case_ignore_votes
                    WHERE case_id = c.id AND active
                ) votes ON TRUE
                WHERE c.id = ?
                """, (rs, rowNum) -> {
            CaseStatus status = CaseStatus.valueOf(rs.getString("status"));
            CaseTransitionPolicy.State state = new CaseTransitionPolicy.State(
                    status, rs.getObject("owner_user_id", UUID.class), instant(rs, "waiting_until"));
            Set<CaseTransitionPolicy.Action> actions = policy.availableActions(state, actor.policyActor());
            return new CaseDetail(
                    rs.getObject("id", UUID.class), rs.getString("reference"), status,
                    new Customer(rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getString("customer_external_ref")),
                    new Owner(rs.getObject("owner_user_id", UUID.class), rs.getString("owner_display_name")),
                    new Channel(rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                            rs.getString("external_channel_id"), rs.getString("grouping_strategy")),
                    new Integration(rs.getObject("integration_id", UUID.class), rs.getString("provider"),
                            rs.getString("integration_display_name"), rs.getString("workspace_external_id"), rs.getString("workspace_name")),
                    new RelatedCase(rs.getObject("related_case_id", UUID.class), rs.getString("related_case_reference")),
                    new PersonalState(rs.getObject("last_read_message_id", UUID.class), instant(rs, "last_read_at"), instant(rs, "snoozed_until")),
                    sla(rs), rs.getInt("ignore_score"), actions.stream().map(Enum::name).sorted().toList(),
                    instant(rs, "claimed_at"), instant(rs, "waiting_until"), instant(rs, "resolved_at"), instant(rs, "ignored_at"),
                    enumValue(rs, "resolution_category"), instant(rs, "created_at"), instant(rs, "updated_at"),
                    instant(rs, "last_activity_at"), rs.getLong("version"));
        }, userId, userId, caseId).stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
    }

    private Actor actor(UUID caseId, UUID userId) {
        return jdbc.query("""
                SELECT u.active, u.role,
                       EXISTS (SELECT 1 FROM case_ignore_votes v WHERE v.case_id = ? AND v.user_id = ?) AS voted
                FROM users u WHERE u.id = ?
                """, (rs, rowNum) -> {
            UserRole role = UserRole.valueOf(rs.getString("role"));
            List<String> permissions = jdbc.query("""
                    SELECT code FROM permissions WHERE ? = 'ADMIN'
                    UNION
                    SELECT permission_code AS code FROM user_permissions WHERE user_id = ?
                    """, (permissionsRs, ignored) -> permissionsRs.getString("code"), role.name(), userId);
            return new Actor(rs.getBoolean("active"), role, Set.copyOf(permissions), rs.getBoolean("voted"), userId);
        }, caseId, userId, userId).stream().findFirst().orElseThrow(ApiProblemException::authenticationRequired);
    }

    private static Sla sla(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID policyId = rs.getObject("policy_id", UUID.class);
        if (policyId == null) return null;
        return new Sla(policyId, instant(rs, "first_response_started_at"), instant(rs, "first_response_due_at"),
                instant(rs, "first_response_completed_at"), instant(rs, "unclaimed_started_at"),
                instant(rs, "unclaimed_warning_at"), instant(rs, "unclaimed_breach_at"), instant(rs, "in_progress_started_at"),
                instant(rs, "in_progress_warning_at"), instant(rs, "in_progress_breach_at"), instant(rs, "paused_at"),
                rs.getLong("total_paused_seconds"), rs.getString("sla_state"), instant(rs, "unclaimed_completed_at"), rs.getString("unclaimed_outcome"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static CaseResolutionCategory enumValue(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? null : CaseResolutionCategory.valueOf(value);
    }

    private record Actor(boolean active, UserRole role, Set<String> permissions, boolean lifetimeIgnoreVoter, UUID id) {
        CaseTransitionPolicy.Actor policyActor() {
            return new CaseTransitionPolicy.Actor(id, active, role, permissions, lifetimeIgnoreVoter);
        }
    }

    record CaseDetail(UUID id, String reference, CaseStatus status, Customer customer, Owner owner, Channel channel,
            Integration integration, RelatedCase relatedCase, PersonalState personalState, Sla sla, int ignoreScore,
            List<String> availableActions, Instant claimedAt, Instant waitingUntil, Instant resolvedAt, Instant ignoredAt,
            CaseResolutionCategory resolutionCategory, Instant createdAt, Instant updatedAt, Instant lastActivityAt, long version) {}
    record Customer(UUID id, String name, String externalRef) {}
    record Owner(UUID id, String displayName) {}
    record Channel(UUID id, String name, String externalChannelId, String groupingStrategy) {}
    record Integration(UUID id, String provider, String displayName, String workspaceExternalId, String workspaceName) {}
    record RelatedCase(UUID id, String reference) {}
    record PersonalState(UUID lastReadMessageId, Instant lastReadAt, Instant snoozedUntil) {}
    record Sla(UUID policyId, Instant firstResponseStartedAt, Instant firstResponseDueAt, Instant firstResponseCompletedAt,
            Instant unclaimedStartedAt, Instant unclaimedWarningAt, Instant unclaimedBreachAt, Instant inProgressStartedAt,
            Instant inProgressWarningAt, Instant inProgressBreachAt, Instant pausedAt, long totalPausedSeconds, String state,
            Instant unclaimedCompletedAt, String unclaimedOutcome) {}
}
