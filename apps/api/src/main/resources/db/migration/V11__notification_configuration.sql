CREATE TABLE notification_destinations (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    name VARCHAR(160) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE RESTRICT,
    target_ref VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    secret_ref VARCHAR(512),
    config_ref VARCHAR(512),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_destinations_name
        CHECK (name = btrim(name) AND length(name) > 0),
    CONSTRAINT chk_notification_destinations_provider
        CHECK (provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT chk_notification_destinations_target_ref
        CHECK (target_ref = btrim(target_ref) AND length(target_ref) > 0),
    CONSTRAINT chk_notification_destinations_secret_ref
        CHECK (secret_ref IS NULL OR (secret_ref = btrim(secret_ref) AND length(secret_ref) > 0)),
    CONSTRAINT chk_notification_destinations_config_ref
        CHECK (config_ref IS NULL OR (config_ref = btrim(config_ref) AND length(config_ref) > 0)),
    CONSTRAINT chk_notification_destinations_version CHECK (version >= 1),
    CONSTRAINT uq_notification_destination_target UNIQUE (integration_id, provider, target_ref)
);

CREATE TABLE notification_rules (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    destination_id UUID NOT NULL REFERENCES notification_destinations(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    event_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    severity_filters JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_rules_name
        CHECK (name = btrim(name) AND length(name) > 0),
    CONSTRAINT chk_notification_rules_event_types CHECK (jsonb_typeof(event_types) = 'array'),
    CONSTRAINT chk_notification_rules_severity_filters CHECK (jsonb_typeof(severity_filters) = 'array'),
    CONSTRAINT chk_notification_rules_version CHECK (version >= 1),
    CONSTRAINT uq_notification_rule_name UNIQUE (destination_id, name)
);

CREATE TABLE notification_configuration_changes (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    entity_type VARCHAR(16) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(255) NOT NULL,
    entity_version BIGINT NOT NULL,
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_changes_entity_type CHECK (entity_type IN ('DESTINATION', 'RULE')),
    CONSTRAINT chk_notification_changes_action CHECK (action IN ('CREATED', 'UPDATED', 'DELETED')),
    CONSTRAINT chk_notification_changes_actor CHECK (actor_ref = btrim(actor_ref) AND length(actor_ref) > 0),
    CONSTRAINT chk_notification_changes_version CHECK (entity_version >= 1),
    CONSTRAINT chk_notification_changes_snapshot CHECK (jsonb_typeof(snapshot_json) = 'object')
);

CREATE INDEX idx_notification_destinations_enabled
    ON notification_destinations (enabled, provider, id);
CREATE INDEX idx_notification_rules_destination_enabled
    ON notification_rules (destination_id, enabled, id);
CREATE INDEX idx_notification_changes_entity
    ON notification_configuration_changes (entity_type, entity_id, created_at DESC);
