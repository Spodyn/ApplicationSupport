-- USI-119 / E10-T06
-- Personal Snooze list projection is scoped by current user and due time.
CREATE INDEX idx_case_snoozes_user_until
    ON case_snoozes (user_id, until_at, case_id);
