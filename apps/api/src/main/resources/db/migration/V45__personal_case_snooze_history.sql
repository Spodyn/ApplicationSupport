CREATE TABLE case_snooze_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action text NOT NULL CHECK (action IN ('CASE_SNOOZE', 'CASE_SNOOZE_CANCELLED', 'CASE_SNOOZE_DUE', 'CASE_SNOOZE_WOKEN')),
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    snoozed_at timestamptz NOT NULL,
    until_at timestamptz NOT NULL
);

CREATE INDEX idx_case_snooze_history_viewer ON case_snooze_history (case_id, user_id, occurred_at DESC, id DESC);

INSERT INTO case_snooze_history (case_id, user_id, action, occurred_at, snoozed_at, until_at)
SELECT case_id, user_id, 'CASE_SNOOZE', created_at, created_at, until_at FROM case_snoozes;
