-- USI-80 / E06-T04
-- Record intentional ignored-channel ingestion without overloading failure fields.

ALTER TABLE inbound_events
    ADD COLUMN processing_outcome varchar(64);

ALTER TABLE inbound_events
    ADD CONSTRAINT ck_inbound_events_processing_outcome_value
        CHECK (processing_outcome IS NULL OR processing_outcome IN ('IGNORED_BY_CHANNEL'));
