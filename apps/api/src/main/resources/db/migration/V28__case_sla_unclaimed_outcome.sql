ALTER TABLE case_sla
    ADD COLUMN unclaimed_completed_at TIMESTAMPTZ,
    ADD COLUMN unclaimed_outcome VARCHAR(16);

ALTER TABLE case_sla
    ADD CONSTRAINT chk_case_sla_unclaimed_outcome
    CHECK (unclaimed_outcome IS NULL OR unclaimed_outcome IN ('ACHIEVED', 'WARNING', 'BREACHED'));
