CREATE TABLE channels (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE RESTRICT,
    external_channel_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    customer_id UUID REFERENCES customers(id) ON DELETE RESTRICT,
    ignored BOOLEAN NOT NULL DEFAULT FALSE,
    grouping_strategy VARCHAR(48) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_message_at TIMESTAMPTZ,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_channels_integration_external UNIQUE (integration_id, external_channel_id),
    CONSTRAINT chk_channels_external_channel_id
        CHECK (external_channel_id = btrim(external_channel_id) AND length(external_channel_id) > 0),
    CONSTRAINT chk_channels_name
        CHECK (name = btrim(name) AND length(name) > 0),
    CONSTRAINT chk_channels_grouping_strategy
        CHECK (grouping_strategy IN (
            'SLACK_ROOT_THREAD',
            'TEAMS_ROOT_REPLIES',
            'TELEGRAM_TOPIC',
            'TELEGRAM_CHAT_ACTIVE_CASE'
        )),
    CONSTRAINT chk_channels_metadata_object
        CHECK (jsonb_typeof(metadata_json) = 'object')
);

CREATE INDEX idx_channels_customer_active
    ON channels (customer_id, active, id);

CREATE INDEX idx_channels_integration_active
    ON channels (integration_id, active, id);

CREATE INDEX idx_channels_last_message
    ON channels (last_message_at DESC, id);
