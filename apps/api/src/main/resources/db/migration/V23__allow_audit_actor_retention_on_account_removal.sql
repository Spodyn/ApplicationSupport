-- Preserve audit history if an account is physically removed. This is the only
-- database-maintained change permitted for an otherwise append-only record.

CREATE OR REPLACE FUNCTION reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.actor_user_id IS NOT NULL
       AND NEW.actor_user_id IS NULL
       AND NEW.id IS NOT DISTINCT FROM OLD.id
       AND NEW.actor_type IS NOT DISTINCT FROM OLD.actor_type
       AND NEW.actor_reference IS NOT DISTINCT FROM OLD.actor_reference
       AND NEW.action IS NOT DISTINCT FROM OLD.action
       AND NEW.entity_type IS NOT DISTINCT FROM OLD.entity_type
       AND NEW.entity_id IS NOT DISTINCT FROM OLD.entity_id
       AND NEW.case_id IS NOT DISTINCT FROM OLD.case_id
       AND NEW.correlation_id IS NOT DISTINCT FROM OLD.correlation_id
       AND NEW.occurred_at IS NOT DISTINCT FROM OLD.occurred_at
       AND NEW.metadata_json IS NOT DISTINCT FROM OLD.metadata_json THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit_events are append-only';
END;
$$;
