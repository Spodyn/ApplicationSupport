-- USI-130 / E12-T04
-- Preserve intentional Slack filtering decisions as technical inbound outcomes.

ALTER TABLE inbound_events
    DROP CONSTRAINT ck_inbound_events_processing_outcome_value;

ALTER TABLE inbound_events
    ADD CONSTRAINT ck_inbound_events_processing_outcome_value
        CHECK (processing_outcome IS NULL OR processing_outcome IN (
            'IGNORED_BY_CHANNEL',
            'UNMAPPED_CHANNEL',
            'INACTIVE_CHANNEL',
            'IGNORED_BOT_MESSAGE',
            'UNSUPPORTED_PROVIDER_EVENT'
        ));
