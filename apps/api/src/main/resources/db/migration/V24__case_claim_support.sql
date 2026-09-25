-- USI-102 / E09-T02
-- Claim needs durable history to enforce the lifetime Ignore-voter restriction.
-- A reset deactivates an active vote but deliberately never deletes that history.

CREATE TABLE case_ignore_votes (
    id uuid NOT NULL DEFAULT uuidv7(),
    case_id uuid NOT NULL,
    user_id uuid NOT NULL,
    weight smallint NOT NULL,
    active boolean NOT NULL DEFAULT TRUE,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deactivated_at timestamptz,

    CONSTRAINT pk_case_ignore_votes PRIMARY KEY (id),
    CONSTRAINT fk_case_ignore_votes_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_ignore_votes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_case_ignore_votes_weight CHECK (weight IN (1, 2)),
    CONSTRAINT ck_case_ignore_votes_deactivation CHECK (
        (active AND deactivated_at IS NULL) OR (NOT active AND deactivated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_case_ignore_votes_active_user
    ON case_ignore_votes (case_id, user_id)
    WHERE active;

CREATE INDEX idx_case_ignore_votes_case_active
    ON case_ignore_votes (case_id, active, id);

-- Snoozes are private, user-scoped projections. Claim removes all of them so a
-- prior reminder cannot fire after ownership has moved to verification.
CREATE TABLE case_snoozes (
    case_id uuid NOT NULL,
    user_id uuid NOT NULL,
    until_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_case_snoozes PRIMARY KEY (case_id, user_id),
    CONSTRAINT fk_case_snoozes_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_case_snoozes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_case_snoozes_until CHECK (until_at > created_at)
);

CREATE INDEX idx_case_snoozes_due ON case_snoozes (until_at, case_id, user_id);
