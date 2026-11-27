-- Database-owned changes (including discovery/resync workers) enter the existing
-- transactional outbox. Only scope and public entity identifiers are recorded.
CREATE FUNCTION application_realtime_hint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    row_data jsonb;
    scope text := TG_ARGV[0];
    entity uuid;
    body jsonb := '{}'::jsonb;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    row_data := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF scope IN ('identity', 'reminders') THEN
        entity := COALESCE((row_data->>'user_id')::uuid, (row_data->>'id')::uuid);
        body := jsonb_build_object('userId', entity);
    ELSIF scope = 'case' THEN
        entity := (row_data->>'case_id')::uuid;
        IF entity IS NULL AND TG_TABLE_NAME = 'attachments' THEN
            SELECT case_id INTO entity FROM messages WHERE id = (row_data->>'message_id')::uuid;
        END IF;
        IF entity IS NULL THEN RETURN NULL; END IF;
        body := jsonb_build_object('caseId', entity);
    ELSE
        entity := '00000000-0000-0000-0000-000000000000'::uuid;
    END IF;
    INSERT INTO outbox_events(type, aggregate_type, aggregate_id, payload_json, correlation_id)
    VALUES ('application.' || scope || '_changed', 'application', uuidv7(), body, uuidv7()::text);
    RETURN NULL;
END $$;

CREATE TRIGGER application_identity AFTER INSERT OR UPDATE OR DELETE ON users
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('identity');
CREATE TRIGGER application_permissions AFTER INSERT OR UPDATE OR DELETE ON user_permissions
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('identity');
CREATE TRIGGER application_weights AFTER INSERT OR UPDATE OR DELETE ON user_ignore_vote_weights
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('identity');
CREATE TRIGGER application_presence AFTER INSERT OR DELETE ON spring_session
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('directory');
CREATE TRIGGER application_presence_heartbeat AFTER UPDATE ON spring_session
FOR EACH ROW WHEN (NEW.last_access_time / 60000 <> OLD.last_access_time / 60000)
EXECUTE FUNCTION application_realtime_hint('directory');

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'customers', 'integrations', 'channels', 'integration_connection_settings',
        'teams_instance_binding', 'integration_resync_settings', 'integration_resync_runs',
        'integration_resync_tasks', 'organization_settings', 'business_hours',
        'business_hour_intervals', 'schedule_exceptions', 'sla_policies',
        'ooo_config', 'notification_rules', 'notification_destinations', 'notification_deliveries'
    ] LOOP
        EXECUTE format('CREATE TRIGGER application_settings AFTER INSERT OR UPDATE OR DELETE ON %I
            FOR EACH ROW EXECUTE FUNCTION application_realtime_hint(''settings'')', table_name);
    END LOOP;
END $$;

CREATE TRIGGER application_attachments AFTER INSERT OR UPDATE OR DELETE ON attachments
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('case');
CREATE TRIGGER application_sla AFTER UPDATE ON case_sla
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('case');
CREATE TRIGGER application_reminder_read AFTER INSERT OR UPDATE ON case_deadline_reminder_recipients
FOR EACH ROW EXECUTE FUNCTION application_realtime_hint('reminders');
