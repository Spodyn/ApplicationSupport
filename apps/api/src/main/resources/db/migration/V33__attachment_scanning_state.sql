ALTER TABLE attachments
    DROP CONSTRAINT chk_attachments_scan_status;

ALTER TABLE attachments
    ADD CONSTRAINT chk_attachments_scan_status
    CHECK (scan_status IN ('PENDING', 'SCANNING', 'CLEAN', 'INFECTED', 'ERROR'));
