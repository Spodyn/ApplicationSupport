-- USI-157 / E15-T03
-- Durable notification delivery state machine. PostgreSQL is the source of truth;
-- RabbitMQ only transports wake-up signals published through the transactional outbox.

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    deduplication_key VARCHAR(255) NOT NULL UNIQUE,
    destination_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    integration_id UUID NOT NULL,
    target_ref VARCHAR(512) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    severity VARCHAR(64),
    payload_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    wake_pending BOOLEAN NOT NULL DEFAULT TRUE,
    attempts INTEGER NOT NULL DEFAULT 0,
    replay_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    last_error_category VARCHAR(32),
    last_error_code VARCHAR(128),
    terminal_reason VARCHAR(128),
    provider_message_ref VARCHAR(512),
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT chk_notification_deliveries_provider
        CHECK (provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT chk_notification_deliveries_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SENT', 'DLQ', 'CANCELLED')),
    CONSTRAINT chk_notification_deliveries_payload
        CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT chk_notification_deliveries_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_notification_deliveries_replay_count CHECK (replay_count >= 0),
    CONSTRAINT chk_notification_deliveries_dedup
        CHECK (deduplication_key = btrim(deduplication_key) AND length(deduplication_key) > 0),
    CONSTRAINT chk_notification_deliveries_target
        CHECK (target_ref = btrim(target_ref) AND length(target_ref) > 0),
    CONSTRAINT chk_notification_deliveries_event_type
        CHECK (event_type = btrim(event_type) AND length(event_type) > 0),
    CONSTRAINT chk_notification_deliveries_severity
        CHECK (severity IS NULL OR (severity = btrim(severity) AND length(severity) > 0)),
    CONSTRAINT chk_notification_deliveries_correlation
        CHECK (correlation_id = btrim(correlation_id) AND length(correlation_id) > 0),
    CONSTRAINT chk_notification_deliveries_lease
        CHECK ((status = 'PROCESSING' AND lease_until IS NOT NULL)
            OR (status <> 'PROCESSING' AND lease_until IS NULL)),
    CONSTRAINT chk_notification_deliveries_wake
        CHECK ((status = 'PENDING' AND wake_pending = TRUE)
            OR (status <> 'PENDING' AND wake_pending = FALSE)),
    CONSTRAINT chk_notification_deliveries_sent
        CHECK ((status = 'SENT' AND sent_at IS NOT NULL)
            OR (status <> 'SENT' AND sent_at IS NULL))
);

CREATE INDEX idx_notification_deliveries_status_due
    ON notification_deliveries (status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');
CREATE INDEX idx_notification_deliveries_processing_lease
    ON notification_deliveries (lease_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_notification_deliveries_destination
    ON notification_deliveries (destination_id, rule_id, created_at DESC);

CREATE TABLE notification_delivery_history (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    delivery_id UUID NOT NULL REFERENCES notification_deliveries(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL,
    reason VARCHAR(128),
    error_category VARCHAR(32),
    error_code VARCHAR(128),
    actor_ref VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_delivery_history_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SENT', 'DLQ', 'CANCELLED')),
    CONSTRAINT chk_notification_delivery_history_attempt CHECK (attempt >= 0),
    CONSTRAINT chk_notification_delivery_history_actor
        CHECK (actor_ref = btrim(actor_ref) AND length(actor_ref) > 0)
);

CREATE INDEX idx_notification_delivery_history_delivery
    ON notification_delivery_history (delivery_id, created_at, id);
