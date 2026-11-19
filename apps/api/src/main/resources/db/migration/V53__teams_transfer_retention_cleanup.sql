-- Transfer state contains no independent business history and must not prevent
-- message purge after the existing attachment/object cleanup has completed.
ALTER TABLE teams_attachment_jobs DROP CONSTRAINT teams_attachment_jobs_message_id_fkey;
ALTER TABLE teams_attachment_jobs ADD CONSTRAINT teams_attachment_jobs_message_id_fkey
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE;
