-- USI-93 / E08-T01
-- Canonical conversation Message persistence with provider deduplication and authorship invariants.

CREATE TABLE messages (
    id uuid NOT NULL DEFAULT uuidv7(),
    case_id uuid NOT NULL,
    external_message_id varchar(255),
    external_thread_key varchar(255),
    kind varchar(16) NOT NULL,
    author_user_id uuid,
    author_external_id varchar(255),
    author_name varchar(200),
    body text NOT NULL,
    body_format varchar(16) NOT NULL DEFAULT 'PLAIN_TEXT',
    inbound boolean NOT NULL,
    delivery_status varchar(16),
    provider_created_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at timestamptz,
    deleted_at timestamptz,
    correlation_id varchar(128) NOT NULL,

    CONSTRAINT pk_messages PRIMARY KEY (id),
    CONSTRAINT fk_messages_case
        FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_author_user
        FOREIGN KEY (author_user_id) REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT ck_messages_external_message_id CHECK (
        external_message_id IS NULL
        OR (
            external_message_id = btrim(external_message_id)
            AND length(external_message_id) > 0
        )
    ),
    CONSTRAINT ck_messages_external_thread_key CHECK (
        external_thread_key IS NULL
        OR (
            external_thread_key = btrim(external_thread_key)
            AND length(external_thread_key) > 0
        )
    ),
    CONSTRAINT ck_messages_kind CHECK (kind IN ('CUSTOMER', 'SUPPORT', 'SYSTEM')),
    CONSTRAINT ck_messages_body_format CHECK (body_format IN ('PLAIN_TEXT', 'MARKDOWN')),
    CONSTRAINT ck_messages_delivery_status CHECK (
        delivery_status IS NULL
        OR delivery_status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'FAILED')
    ),
    CONSTRAINT ck_messages_author_external_id CHECK (
        author_external_id IS NULL
        OR (
            author_external_id = btrim(author_external_id)
            AND length(author_external_id) > 0
        )
    ),
    CONSTRAINT ck_messages_author_name CHECK (
        author_name IS NULL
        OR (
            author_name = btrim(author_name)
            AND length(author_name) > 0
        )
    ),
    CONSTRAINT ck_messages_correlation_id CHECK (
        correlation_id = btrim(correlation_id)
        AND length(correlation_id) > 0
    ),
    CONSTRAINT ck_messages_authorship CHECK (
        (
            kind = 'CUSTOMER'
            AND inbound = TRUE
            AND external_message_id IS NOT NULL
            AND provider_created_at IS NOT NULL
            AND author_user_id IS NULL
            AND author_external_id IS NOT NULL
            AND delivery_status IS NULL
        )
        OR (
            kind = 'SUPPORT'
            AND inbound = FALSE
            AND author_user_id IS NOT NULL
            AND author_external_id IS NULL
            AND delivery_status IS NOT NULL
        )
        OR (
            kind = 'SYSTEM'
            AND inbound = FALSE
            AND author_user_id IS NULL
            AND author_external_id IS NULL
            AND delivery_status IS NULL
        )
    )
);

CREATE UNIQUE INDEX uq_messages_case_external_message
    ON messages (case_id, external_message_id)
    WHERE external_message_id IS NOT NULL;

CREATE INDEX idx_messages_case_provider_order
    ON messages (case_id, provider_created_at, id);

CREATE INDEX idx_messages_case_created_order
    ON messages (case_id, created_at, id);
