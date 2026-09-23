-- USI-80 / E06-T04
-- Keep successful ignored-channel ingestion distinct from processing failures.

ALTER TABLE inbound_events
    ADD COLUMN processing_outcome varchar(64);

UPDATE inbound_events
SET processing_outcome = 'HANDLED'
WHERE status = 'PROCESSED';

ALTER TABLE inbound_events
    ADD CONSTRAINT ck_inbound_events_processing_outcome_value
        CHECK (processing_outcome IS NULL OR processing_outcome IN ('HANDLED', 'IGNORED_BY_CHANNEL')),
    ADD CONSTRAINT ck_inbound_events_processing_outcome_status
        CHECK (
            (status = 'PROCESSED' AND processing_outcome IS NOT NULL)
            OR (status <> 'PROCESSED' AND processing_outcome IS NULL)
        );
