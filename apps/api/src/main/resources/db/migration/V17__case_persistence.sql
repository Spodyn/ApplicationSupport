-- USI-84 / E07-T01
-- Canonical Case persistence model and provider/grouping concurrency constraints.

CREATE SEQUENCE case_reference_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1;

CREATE TABLE cases (
    id uuid NOT NULL DEFAULT uuidv7(),
    reference varchar(32) NOT NULL DEFAULT (
        'CASE-' || lpad(nextval('case_reference_seq')::text, 8, '0')
    ),
    customer_id uuid NOT NULL,
    integration_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    provider varchar(16) NOT NULL,
    external_conversation_id varchar(255) NOT NULL,
    external_thread_key varchar(255),
    status varchar(32) NOT NULL DEFAULT 'NEW',
    owner_user_id uuid,
    claimed_at timestamptz,
    waiting_until timestamptz,
    resolved_at timestamptz,
    ignored_at timestamptz,
    resolution_category varchar(32),
    related_case_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT pk_cases PRIMARY KEY (id),
    CONSTRAINT uq_cases_reference UNIQUE (reference),
    CONSTRAINT fk_cases_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cases_integration
        FOREIGN KEY (integration_id) REFERENCES integrations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cases_channel
        FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cases_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cases_related_case
        FOREIGN KEY (related_case_id) REFERENCES cases(id) ON DELETE RESTRICT,

    CONSTRAINT ck_cases_reference CHECK (
        reference = btrim(reference)
        AND reference ~ '^CASE-[0-9]{8,}$'
    ),
    CONSTRAINT ck_cases_provider CHECK (provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT ck_cases_external_conversation_id CHECK (
        external_conversation_id = btrim(external_conversation_id)
        AND length(external_conversation_id) > 0
    ),
    CONSTRAINT ck_cases_external_thread_key CHECK (
        external_thread_key IS NULL
        OR (
            external_thread_key = btrim(external_thread_key)
            AND length(external_thread_key) > 0
        )
    ),
    CONSTRAINT ck_cases_status CHECK (
        status IN (
            'NEW',
            'VERIFICATION',
            'WAITING_FOR_CUSTOMER',
            'PARTIALLY_IGNORED',
            'IGNORED',
            'RESOLVED'
        )
    ),
    CONSTRAINT ck_cases_owner_state CHECK (
        (status = 'VERIFICATION' AND owner_user_id IS NOT NULL)
        OR (status IN ('NEW', 'WAITING_FOR_CUSTOMER', 'PARTIALLY_IGNORED', 'IGNORED') AND owner_user_id IS NULL)
        OR status = 'RESOLVED'
    ),
    CONSTRAINT ck_cases_claimed_at CHECK (
        owner_user_id IS NULL OR claimed_at IS NOT NULL
    ),
    CONSTRAINT ck_cases_waiting_until CHECK (
        (status = 'WAITING_FOR_CUSTOMER' AND waiting_until IS NOT NULL)
        OR (status <> 'WAITING_FOR_CUSTOMER' AND waiting_until IS NULL)
    ),
    CONSTRAINT ck_cases_resolved_at CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL)
    ),
    CONSTRAINT ck_cases_ignored_at CHECK (
        (status = 'IGNORED' AND ignored_at IS NOT NULL)
        OR (status <> 'IGNORED' AND ignored_at IS NULL)
    ),
    CONSTRAINT ck_cases_resolution_category CHECK (
        resolution_category IS NULL
        OR (
            status = 'RESOLVED'
            AND resolution_category IN ('SOLVED', 'NO_ACTION_REQUIRED', 'DUPLICATE', 'OTHER')
        )
    ),
    CONSTRAINT ck_cases_related_not_self CHECK (
        related_case_id IS NULL OR related_case_id <> id
    ),
    CONSTRAINT ck_cases_version CHECK (version >= 0)
);

ALTER SEQUENCE case_reference_seq OWNED BY cases.reference;

CREATE INDEX idx_cases_status_activity
    ON cases (status, last_activity_at DESC, id DESC);

CREATE INDEX idx_cases_owner_status
    ON cases (owner_user_id, status, last_activity_at DESC, id DESC)
    WHERE owner_user_id IS NOT NULL;

CREATE INDEX idx_cases_channel_thread
    ON cases (
        channel_id,
        external_conversation_id,
        external_thread_key,
        created_at DESC,
        id DESC
    );

CREATE INDEX idx_cases_customer_activity
    ON cases (customer_id, last_activity_at DESC, id DESC);

-- Exactly one non-terminal Case may exist for a provider grouping context.
-- NULL thread keys represent single-active-case contexts such as Teams group chats
-- and Telegram chats without topics.
CREATE UNIQUE INDEX uq_cases_active_provider_context
    ON cases (
        integration_id,
        channel_id,
        provider,
        external_conversation_id,
        COALESCE(external_thread_key, '')
    )
    WHERE status NOT IN ('IGNORED', 'RESOLVED');
