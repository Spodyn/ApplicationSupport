-- USI-114 / E10-T01
-- Per-user Case read position. No cached unread_count: Message history remains source of truth.

ALTER TABLE messages
    ADD CONSTRAINT uq_messages_case_id_id UNIQUE (case_id, id);

CREATE TABLE case_read_states (
    user_id uuid NOT NULL,
    case_id uuid NOT NULL,
    last_read_message_id uuid,
    last_read_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_case_read_states PRIMARY KEY (user_id, case_id),
    CONSTRAINT fk_case_read_states_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_case_read_states_case
        FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_case_read_states_message_in_case
        FOREIGN KEY (case_id, last_read_message_id)
        REFERENCES messages(case_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_case_read_states_position_pair CHECK (
        (last_read_message_id IS NULL AND last_read_at IS NULL)
        OR (last_read_message_id IS NOT NULL AND last_read_at IS NOT NULL)
    )
);

CREATE INDEX idx_case_read_states_case_user
    ON case_read_states (case_id, user_id);

CREATE INDEX idx_case_read_states_user_updated
    ON case_read_states (user_id, updated_at DESC, case_id);
