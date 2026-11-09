CREATE TABLE user_ignore_vote_weights (
    user_id uuid PRIMARY KEY,
    weight smallint NOT NULL,
    valid_until timestamptz,
    configured_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_ignore_vote_weights_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_ignore_vote_weights_actor
        FOREIGN KEY (configured_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_ignore_vote_weights_weight CHECK (weight IN (1, 2))
);
