-- Sparse account preferences preserve the unified inbox for existing accounts.
CREATE TABLE user_inbox_preferences (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    mode varchar(32) NOT NULL CHECK (mode IN ('UNIFIED', 'SEPARATE_BY_PLATFORM'))
);

-- Personal hints use the same durable, commit-bound outbox as other projections.
CREATE FUNCTION inbox_preferences_realtime_hint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NULL; END IF;
    INSERT INTO outbox_events(type, aggregate_type, aggregate_id, payload_json, correlation_id)
    VALUES ('application.preferences_changed', 'application', uuidv7(),
            jsonb_build_object('userId', NEW.user_id), uuidv7()::text);
    RETURN NULL;
END $$;

CREATE TRIGGER application_inbox_preferences AFTER INSERT OR UPDATE ON user_inbox_preferences
FOR EACH ROW EXECUTE FUNCTION inbox_preferences_realtime_hint();
