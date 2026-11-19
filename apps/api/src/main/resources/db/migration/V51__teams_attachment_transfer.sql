-- Additive, provider-owned durable transfer state. N-1 safely ignores these tables.
CREATE TABLE teams_attachment_jobs (
    message_id uuid PRIMARY KEY REFERENCES messages(id),
    inbound_event_id uuid REFERENCES inbound_events(id) ON DELETE SET NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','FAILED','DONE')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_teams_attachment_jobs_due ON teams_attachment_jobs(next_attempt_at, message_id)
    WHERE status <> 'DONE';

CREATE TABLE teams_outbound_files (
    attachment_id uuid PRIMARY KEY REFERENCES attachments(id) ON DELETE CASCADE,
    drive_id varchar(255) NOT NULL,
    item_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
