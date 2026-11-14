ALTER TABLE attachments
    ALTER COLUMN storage_key DROP NOT NULL,
    ALTER COLUMN sha256 DROP NOT NULL,
    ADD COLUMN slack_ingest_pending boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN slack_ingest_token uuid,
    ADD COLUMN slack_ingest_until timestamptz,
    ADD CONSTRAINT chk_attachments_object_reference CHECK (
        (storage_key IS NOT NULL AND sha256 IS NOT NULL)
        OR (storage_key IS NULL AND sha256 IS NULL AND message_id IS NOT NULL
            AND provider_file_id IS NOT NULL AND scan_status IN ('PENDING', 'ERROR'))
    );

CREATE INDEX idx_attachments_slack_ingest_due ON attachments (created_at, id)
    WHERE slack_ingest_pending;
