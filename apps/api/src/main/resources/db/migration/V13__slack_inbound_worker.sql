-- USI-129 / E12-T03
-- Durable async inbound retry/DLQ metadata. PostgreSQL remains the source of truth;
-- RabbitMQ transports wake-up signals only.

ALTER TABLE inbound_events
    ADD COLUMN failure_category varchar(32),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN wake_pending boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN dead_lettered_at timestamptz;

ALTER TABLE inbound_events
    DROP CONSTRAINT ck_inbound_events_status;

ALTER TABLE inbound_events
    ADD CONSTRAINT ck_inbound_events_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'DLQ')),
    ADD CONSTRAINT ck_inbound_events_failure_category
        CHECK (failure_category IS NULL OR failure_category IN ('TRANSIENT', 'PERMANENT', 'MALFORMED', 'EXHAUSTED')),
    ADD CONSTRAINT ck_inbound_events_dead_letter
        CHECK (
            (status = 'DLQ' AND dead_lettered_at IS NOT NULL AND failure_category IS NOT NULL)
            OR (status <> 'DLQ' AND dead_lettered_at IS NULL)
        );

CREATE INDEX idx_inbound_events_provider_due
    ON inbound_events (provider, status, next_attempt_at, id)
    WHERE status IN ('RECEIVED', 'FAILED') AND wake_pending = FALSE;
