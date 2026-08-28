-- USI-63 / E04-T02
-- Durable one-shot state for the controlled first-administrator bootstrap.

CREATE TABLE bootstrap_admin_state (
    id smallint PRIMARY KEY,
    consumed boolean NOT NULL DEFAULT false,
    consumed_at timestamptz,
    admin_user_id uuid,
    CONSTRAINT ck_bootstrap_admin_state_singleton CHECK (id = 1),
    CONSTRAINT ck_bootstrap_admin_state_consistency CHECK (
        (NOT consumed AND consumed_at IS NULL AND admin_user_id IS NULL)
        OR (consumed AND consumed_at IS NOT NULL AND admin_user_id IS NOT NULL)
    ),
    CONSTRAINT fk_bootstrap_admin_state_user
        FOREIGN KEY (admin_user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT
);

INSERT INTO bootstrap_admin_state (id, consumed)
VALUES (1, false);
