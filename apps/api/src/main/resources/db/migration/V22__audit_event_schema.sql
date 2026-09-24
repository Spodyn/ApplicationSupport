-- USI-171 / E17-T01
-- Immutable, privacy-safe audit history. Audit records intentionally retain a
-- nullable actor reference so account deletion/deactivation cannot erase history.

CREATE TABLE audit_events (
    id uuid NOT NULL DEFAULT uuidv7(),
    actor_type varchar(16) NOT NULL,
    actor_user_id uuid,
    actor_reference varchar(255),
    action varchar(128) NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid NOT NULL,
    case_id uuid,
    correlation_id varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_events_case
        FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_events_actor_type
        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'PROVIDER')),
    CONSTRAINT ck_audit_events_actor_reference
        CHECK (actor_reference IS NULL OR (
            actor_reference = btrim(actor_reference) AND length(actor_reference) > 0
        )),
    CONSTRAINT ck_audit_events_action
        CHECK (action = btrim(action) AND length(action) BETWEEN 1 AND 128),
    CONSTRAINT ck_audit_events_entity_type
        CHECK (entity_type = btrim(entity_type) AND length(entity_type) BETWEEN 1 AND 64),
    CONSTRAINT ck_audit_events_correlation_id
        CHECK (correlation_id = btrim(correlation_id) AND length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_audit_events_metadata_object
        CHECK (jsonb_typeof(metadata_json) = 'object')
);

CREATE INDEX idx_audit_events_case_occurred
    ON audit_events (case_id, occurred_at DESC, id DESC)
    WHERE case_id IS NOT NULL;

CREATE INDEX idx_audit_events_entity_occurred
    ON audit_events (entity_type, entity_id, occurred_at DESC, id DESC);

CREATE INDEX idx_audit_events_occurred
    ON audit_events (occurred_at DESC, id DESC);

CREATE FUNCTION reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events are append-only';
END;
$$;

CREATE TRIGGER audit_events_prevent_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TRIGGER audit_events_prevent_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();
