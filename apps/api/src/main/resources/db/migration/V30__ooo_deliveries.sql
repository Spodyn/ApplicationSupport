CREATE TABLE ooo_deliveries (
    case_id uuid NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    closure_key varchar(64) NOT NULL,
    message_id uuid NOT NULL UNIQUE REFERENCES messages(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ooo_deliveries PRIMARY KEY (case_id, closure_key),
    CONSTRAINT chk_ooo_deliveries_closure_key CHECK (closure_key = btrim(closure_key) AND length(closure_key) > 0)
);

ALTER TABLE messages DROP CONSTRAINT ck_messages_authorship;
ALTER TABLE messages ADD CONSTRAINT ck_messages_authorship CHECK (
    (kind = 'CUSTOMER' AND inbound = TRUE AND external_message_id IS NOT NULL AND provider_created_at IS NOT NULL
        AND author_user_id IS NULL AND author_external_id IS NOT NULL AND delivery_status IS NULL)
    OR (kind = 'SUPPORT' AND inbound = FALSE AND author_user_id IS NOT NULL AND author_external_id IS NULL AND delivery_status IS NOT NULL)
    OR (kind = 'SYSTEM' AND inbound = FALSE AND author_user_id IS NULL AND author_external_id IS NULL)
);
