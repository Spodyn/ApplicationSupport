ALTER TABLE attachments
    ADD COLUMN case_id UUID NULL REFERENCES cases(id) ON DELETE RESTRICT;

UPDATE attachments a
SET case_id = m.case_id
FROM messages m
WHERE a.message_id = m.id
  AND a.case_id IS NULL;

CREATE OR REPLACE FUNCTION set_attachment_case_from_message()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.case_id IS NULL AND NEW.message_id IS NOT NULL THEN
        SELECT m.case_id INTO NEW.case_id
        FROM messages m
        WHERE m.id = NEW.message_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_attachments_set_case_from_message
BEFORE INSERT OR UPDATE OF message_id ON attachments
FOR EACH ROW
EXECUTE FUNCTION set_attachment_case_from_message();

CREATE INDEX idx_attachments_case_created
    ON attachments (case_id, created_at, id)
    WHERE case_id IS NOT NULL;

CREATE INDEX idx_attachments_orphan_cleanup
    ON attachments (created_at, id)
    WHERE message_id IS NULL;
