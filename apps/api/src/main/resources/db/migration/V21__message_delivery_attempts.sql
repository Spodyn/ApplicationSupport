-- USI-96 / E08-T04
-- Durable outbound Message delivery attempts and retry scheduling.

CREATE TABLE delivery_attempts (
    id uuid NOT NULL DEFAULT uuidv7(),
    message_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamptz,
    error_category varchar(16),
    error_code varchar(128),
    next_retry_at timestamptz,
    provider_response_ref varchar(512),

    CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_delivery_attempts_message
        FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT uq_delivery_attempts_message_attempt UNIQUE (message_id, attempt_no),
    CONSTRAINT ck_delivery_attempts_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_delivery_attempts_error_category CHECK (
        error_category IS NULL OR error_category IN ('TRANSIENT', 'PERMANENT')
    ),
    CONSTRAINT ck_delivery_attempts_error_code CHECK (
        error_code IS NULL
        OR (error_code = btrim(error_code) AND length(error_code) > 0)
    ),
    CONSTRAINT ck_delivery_attempts_provider_ref CHECK (
        provider_response_ref IS NULL
        OR (provider_response_ref = btrim(provider_response_ref) AND length(provider_response_ref) > 0)
    ),
    CONSTRAINT ck_delivery_attempts_completion CHECK (
        finished_at IS NULL OR finished_at >= started_at
    ),
    CONSTRAINT ck_delivery_attempts_active_lease CHECK (
        finished_at IS NOT NULL OR next_retry_at IS NOT NULL
    ),
    CONSTRAINT ck_delivery_attempts_failure CHECK (
        error_category IS NULL OR finished_at IS NOT NULL
    )
);

CREATE INDEX idx_delivery_attempts_message_started
    ON delivery_attempts (message_id, started_at DESC, attempt_no DESC);

CREATE INDEX idx_delivery_attempts_due_retry
    ON delivery_attempts (next_retry_at, message_id)
    WHERE finished_at IS NOT NULL AND next_retry_at IS NOT NULL;

CREATE INDEX idx_delivery_attempts_active_lease
    ON delivery_attempts (next_retry_at, message_id)
    WHERE finished_at IS NULL;
