CREATE TABLE case_internal_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    author_name VARCHAR(200) NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 10000 AND btrim(body) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);

CREATE INDEX idx_case_internal_notes_history
    ON case_internal_notes (case_id, created_at DESC, id DESC);
