CREATE TABLE case_sla (
    case_id UUID PRIMARY KEY REFERENCES cases(id) ON DELETE CASCADE,
    policy_id UUID NOT NULL REFERENCES sla_policies(id) ON DELETE RESTRICT,
    first_response_started_at TIMESTAMPTZ NOT NULL,
    first_response_due_at TIMESTAMPTZ NOT NULL,
    first_response_completed_at TIMESTAMPTZ,
    unclaimed_started_at TIMESTAMPTZ NOT NULL,
    unclaimed_warning_at TIMESTAMPTZ NOT NULL,
    unclaimed_breach_at TIMESTAMPTZ NOT NULL,
    in_progress_started_at TIMESTAMPTZ,
    in_progress_warning_at TIMESTAMPTZ,
    in_progress_breach_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    total_paused_seconds BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL DEFAULT 'ON_TRACK',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_case_sla_pause CHECK (total_paused_seconds >= 0),
    CONSTRAINT chk_case_sla_state CHECK (state IN ('ON_TRACK', 'WARNING', 'BREACHED', 'PAUSED'))
);
CREATE INDEX idx_case_sla_first_response_due ON case_sla (first_response_due_at, state) WHERE first_response_completed_at IS NULL;
CREATE INDEX idx_case_sla_unclaimed_breach ON case_sla (unclaimed_breach_at, state);
CREATE INDEX idx_case_sla_in_progress_breach ON case_sla (in_progress_breach_at, state) WHERE in_progress_breach_at IS NOT NULL;
