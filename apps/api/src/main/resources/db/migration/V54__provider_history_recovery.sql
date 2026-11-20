-- Additive recovery state. N-1 ignores these opt-in tables/nullable columns.
CREATE TABLE integration_resync_settings (
    integration_id uuid PRIMARY KEY REFERENCES integrations(id),
    enabled boolean NOT NULL DEFAULT FALSE,
    automatic boolean NOT NULL DEFAULT FALSE,
    interval_minutes integer NOT NULL DEFAULT 30 CHECK (interval_minutes BETWEEN 1 AND 10080),
    lookback_minutes integer NOT NULL DEFAULT 2880 CHECK (lookback_minutes BETWEEN 1 AND 5256000),
    provider_retry_at timestamptz NOT NULL DEFAULT '-infinity',
    last_run_at timestamptz,
    next_run_at timestamptz,
    CHECK (NOT automatic OR enabled)
);
CREATE TABLE integration_resync_runs (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    integration_id uuid NOT NULL REFERENCES integrations(id),
    source varchar(16) NOT NULL CHECK (source IN ('manual','automatic')),
    from_at timestamptz,
    until_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE integration_resync_tasks (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL REFERENCES integration_resync_runs(id),
    channel_id uuid NOT NULL REFERENCES channels(id),
    thread_key varchar(255) NOT NULL DEFAULT '',
    cursor text,
    status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','WAITING','SUCCEEDED','FAILED')),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    scanned bigint NOT NULL DEFAULT 0 CHECK (scanned >= 0),
    error_code varchar(128),
    UNIQUE (run_id,channel_id,thread_key)
);
CREATE INDEX idx_integration_resync_due ON integration_resync_tasks(next_attempt_at,id)
    WHERE status IN ('QUEUED','RUNNING','WAITING');
CREATE INDEX idx_integration_resync_runs ON integration_resync_runs(integration_id,created_at DESC);
ALTER TABLE inbound_events ADD COLUMN resync_task_id uuid REFERENCES integration_resync_tasks(id);
ALTER TABLE messages ADD COLUMN recovery_source varchar(16) CHECK (recovery_source IN ('manual','automatic'));
ALTER TABLE messages ADD COLUMN resync_task_id uuid REFERENCES integration_resync_tasks(id);
CREATE INDEX idx_messages_resync_task ON messages(resync_task_id) WHERE resync_task_id IS NOT NULL;
CREATE INDEX idx_inbound_resync_task ON inbound_events(resync_task_id) WHERE resync_task_id IS NOT NULL;
