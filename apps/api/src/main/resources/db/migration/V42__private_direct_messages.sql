CREATE TABLE direct_conversations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    low_user_id uuid NOT NULL REFERENCES users (id),
    high_user_id uuid NOT NULL REFERENCES users (id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    low_read_through bigint NOT NULL DEFAULT 0,
    high_read_through bigint NOT NULL DEFAULT 0,
    CHECK (low_user_id < high_user_id),
    UNIQUE (low_user_id, high_user_id)
);

CREATE INDEX ix_direct_conversations_low ON direct_conversations (low_user_id, updated_at DESC, id DESC);
CREATE INDEX ix_direct_conversations_high ON direct_conversations (high_user_id, updated_at DESC, id DESC);

CREATE TABLE direct_messages (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    conversation_id uuid NOT NULL REFERENCES direct_conversations (id),
    sender_id uuid NOT NULL REFERENCES users (id),
    position bigint GENERATED ALWAYS AS IDENTITY,
    body varchar(4000) NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (conversation_id, position)
);

CREATE INDEX ix_direct_messages_unread ON direct_messages (conversation_id, sender_id, position);

CREATE TABLE direct_message_write_budgets (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    minute_start timestamptz NOT NULL,
    writes integer NOT NULL CHECK (writes BETWEEN 1 AND 60)
);
