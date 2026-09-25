CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    message_id UUID NULL REFERENCES messages(id) ON DELETE RESTRICT,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    original_filename VARCHAR(512) NOT NULL,
    content_type VARCHAR(255),
    detected_content_type VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    scan_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    scan_error VARCHAR(1024),
    provider_file_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_attachments_size_non_negative CHECK (size_bytes >= 0),
    CONSTRAINT chk_attachments_sha256_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_attachments_scan_status CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR')),
    CONSTRAINT chk_attachments_scan_error CHECK (
        (scan_status = 'ERROR' AND scan_error IS NOT NULL AND btrim(scan_error) <> '')
        OR (scan_status <> 'ERROR' AND scan_error IS NULL)
    )
);

CREATE INDEX idx_attachments_message_created
    ON attachments (message_id, created_at, id)
    WHERE message_id IS NOT NULL;

CREATE INDEX idx_attachments_provider_file
    ON attachments (provider_file_id)
    WHERE provider_file_id IS NOT NULL;
