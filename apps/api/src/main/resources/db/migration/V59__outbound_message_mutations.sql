ALTER TABLE messages ADD COLUMN mutation_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN provider_mutated_at timestamptz;

-- Real provider identities only. Connector acceptance without an ID creates no target.
CREATE TABLE message_mutation_targets (
    message_id uuid PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    provider_message_ref varchar(512) NOT NULL,
    conversation_id text NOT NULL,
    sender_id text,
    service_url text,
    context_type varchar(32),
    attachment_payload jsonb NOT NULL DEFAULT '[]',
    credential_ref text
);

CREATE TABLE message_mutations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    actor_user_id uuid NOT NULL REFERENCES users(id),
    action varchar(8) NOT NULL CHECK (action IN ('EDIT', 'DELETE')),
    expected_revision bigint NOT NULL,
    requested_body text,
    state varchar(16) NOT NULL DEFAULT 'QUEUED'
      CHECK (state IN ('QUEUED','RUNNING','RETRY','CONFIRMED','FAILED','UNCERTAIN')),
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 8,
    retry_until timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    next_attempt_at timestamptz,
    dispatched_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(128),
    retryable boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    CHECK ((action='EDIT' AND requested_body IS NOT NULL) OR (action='DELETE' AND requested_body IS NULL))
);
CREATE UNIQUE INDEX one_active_message_mutation ON message_mutations(message_id)
WHERE state IN ('QUEUED','RUNNING','RETRY','UNCERTAIN');
CREATE INDEX message_mutation_history ON message_mutations(message_id, sequence DESC);
CREATE INDEX message_mutation_due ON message_mutations(next_attempt_at)
WHERE state IN ('QUEUED','RETRY');
CREATE INDEX message_mutation_leases ON message_mutations(lease_until) WHERE state='RUNNING';

-- Message content stays out of audit metadata; these versions share Message retention.
CREATE TABLE message_content_versions (
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    body text,
    body_format varchar(16) NOT NULL,
    edited_at timestamptz,
    deleted_at timestamptz,
    retained_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(message_id, revision)
);
