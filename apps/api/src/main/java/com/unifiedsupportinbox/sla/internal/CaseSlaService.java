package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.sla.SlaPolicyView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaseSlaService implements CaseSlaInitializer {
    private final JdbcTemplate jdbc;
    private final SlaPolicyRepository policies;
    CaseSlaService(JdbcTemplate jdbc, SlaPolicyRepository policies) { this.jdbc = jdbc; this.policies = policies; }
    @Override @Transactional
    public void initialize(UUID caseId, Instant createdAt) {
        SlaPolicyView policy = policies.active();
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO case_sla (case_id, policy_id, first_response_started_at, first_response_due_at, unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (case_id) DO NOTHING
                """, caseId, policy.id(), startedAt,
                OffsetDateTime.ofInstant(createdAt.plusSeconds(policy.firstResponseMinutes() * 60), ZoneOffset.UTC), startedAt,
                OffsetDateTime.ofInstant(createdAt.plusSeconds(policy.unclaimedWarningMinutes() * 60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt.plusSeconds(policy.unclaimedBreachMinutes() * 60), ZoneOffset.UTC));
    }
}
