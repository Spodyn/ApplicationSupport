-- USI-136 / E12-T10
-- Durable, bounded Slack recovery/backfill jobs. PostgreSQL remains the source of
-- truth; provider history is used only by explicit/admin recovery flows.

CREATE TABLE slack_resync_jobs (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    external_channel_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    oldest_ts VARCHAR(32) NOT NULL,
    latest_ts VARCHAR(32) NOT NULL,
    cursor VARCHAR(1024),
    fetched_messages INTEGER NOT NULL DEFAULT 0,
    scheduled_events INTEGER NOT NULL DEFAULT 0,
    max_messages INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    CONSTRAINT ck_slack_resync_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_slack_resync_external_channel CHECK (
        external_channel_id = btrim(external_channel_id)
        AND length(external_channel_id) > 0
    ),
    CONSTRAINT ck_slack_resync_oldest CHECK (
        oldest_ts = btrim(oldest_ts) AND length(oldest_ts) > 0
    ),
    CONSTRAINT ck_slack_resync_latest CHECK (
        latest_ts = btrim(latest_ts) AND length(latest_ts) > 0
    ),
    CONSTRAINT ck_slack_resync_cursor CHECK (
        cursor IS NULL OR (cursor = btrim(cursor) AND length(cursor) > 0)
    ),
    CONSTRAINT ck_slack_resync_counts CHECK (
        fetched_messages >= 0
        AND scheduled_events >= 0
        AND max_messages BETWEEN 1 AND 500
        AND fetched_messages <= max_messages
    ),
    CONSTRAINT ck_slack_resync_error CHECK (
        last_error_code IS NULL OR (
            last_error_code = btrim(last_error_code)
            AND length(last_error_code) > 0
        )
    ),
    CONSTRAINT ck_slack_resync_completion CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL)
        OR (status <> 'SUCCEEDED')
    )
);

CREATE INDEX idx_slack_resync_jobs_due
    ON slack_resync_jobs (next_attempt_at, created_at, id)
    WHERE status IN ('QUEUED', 'WAITING', 'RUNNING');

CREATE INDEX idx_slack_resync_jobs_integration_channel
    ON slack_resync_jobs (integration_id, channel_id, created_at DESC, id);
