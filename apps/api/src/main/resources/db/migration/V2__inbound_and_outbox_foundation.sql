-- USI-60 / E03-T05
-- Durable provider inbox + transactional outbox foundation.

CREATE TABLE inbound_events (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    provider varchar(32) NOT NULL,
    integration_id uuid NOT NULL,
    external_event_id varchar(255) NOT NULL,
    payload_json jsonb NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at timestamptz,
    error_code varchar(128),
    attempts integer NOT NULL DEFAULT 0,
    correlation_id varchar(128) NOT NULL,
    CONSTRAINT uq_inbound_events_integration_external_event UNIQUE (integration_id, external_event_id),
    CONSTRAINT ck_inbound_events_provider CHECK (provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT ck_inbound_events_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_inbound_events_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_inbound_events_correlation_id CHECK (char_length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_inbound_events_processed_at CHECK (
        (status = 'PROCESSED' AND processed_at IS NOT NULL)
        OR (status <> 'PROCESSED' AND processed_at IS NULL)
    )
);

CREATE INDEX idx_inbound_events_status_received
    ON inbound_events (status, received_at, id);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    type varchar(128) NOT NULL,
    aggregate_type varchar(128) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload_json jsonb NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_events_correlation_id CHECK (char_length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_outbox_events_published_at CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX idx_outbox_events_status_due
    ON outbox_events (status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'PROCESSING');
