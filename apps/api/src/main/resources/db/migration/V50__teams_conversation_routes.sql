-- Additive provider-owned routing state; N-1 applications ignore this table.
CREATE TABLE teams_conversation_routes (
    integration_id uuid NOT NULL REFERENCES integrations(id),
    external_channel_id varchar(255) NOT NULL,
    thread_key varchar(255) NOT NULL,
    connector_conversation_id varchar(1024) NOT NULL,
    service_url varchar(2048) NOT NULL,
    bot_id varchar(255) NOT NULL,
    context_type varchar(32) NOT NULL CHECK (context_type IN ('standardChannel', 'groupChat')),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integration_id, external_channel_id, thread_key)
);
