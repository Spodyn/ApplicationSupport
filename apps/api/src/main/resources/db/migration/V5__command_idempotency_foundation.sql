-- USI-55 / E02-T08
-- Durable 24-hour command idempotency records. Business writes and the completed
-- replay response are committed in the same transaction as the owning row.

CREATE TABLE idempotency_keys (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid NOT NULL,
    command_scope varchar(128) NOT NULL,
    "key" varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    response_status smallint,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    CONSTRAINT fk_idempotency_keys_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ux_idempotency_keys_user_scope_key
        UNIQUE (user_id, command_scope, "key"),
    CONSTRAINT ck_idempotency_keys_scope
        CHECK (char_length(btrim(command_scope)) BETWEEN 1 AND 128),
    CONSTRAINT ck_idempotency_keys_key
        CHECK (char_length("key") BETWEEN 1 AND 128),
    CONSTRAINT ck_idempotency_keys_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_keys_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_idempotency_keys_response_complete
        CHECK ((response_status IS NULL) = (response_body IS NULL)),
    CONSTRAINT ck_idempotency_keys_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_keys_expires_at
    ON idempotency_keys (expires_at, id);
