ALTER TABLE attachments
    ADD COLUMN case_id UUID NULL REFERENCES cases(id) ON DELETE RESTRICT;

UPDATE attachments a
SET case_id = m.case_id
FROM messages m
WHERE a.message_id = m.id
  AND a.case_id IS NULL;

CREATE INDEX idx_attachments_case_created
    ON attachments (case_id, created_at, id)
    WHERE case_id IS NOT NULL;

CREATE INDEX idx_attachments_orphan_cleanup
    ON attachments (created_at, id)
    WHERE message_id IS NULL;
