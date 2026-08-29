CREATE TABLE integrations (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    provider VARCHAR(16) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIGURING',
    health VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    workspace_external_id VARCHAR(255),
    workspace_name VARCHAR(255),
    secret_ref VARCHAR(512),
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_event_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_integrations_provider
        CHECK (provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT chk_integrations_status
        CHECK (status IN ('CONFIGURING', 'ENABLED', 'DISABLED')),
    CONSTRAINT chk_integrations_health
        CHECK (health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT chk_integrations_display_name
        CHECK (display_name = btrim(display_name) AND length(display_name) > 0),
    CONSTRAINT chk_integrations_workspace_external_id
        CHECK (workspace_external_id IS NULL OR (
            workspace_external_id = btrim(workspace_external_id)
            AND length(workspace_external_id) > 0
        )),
    CONSTRAINT chk_integrations_workspace_name
        CHECK (workspace_name IS NULL OR (
            workspace_name = btrim(workspace_name)
            AND length(workspace_name) > 0
        )),
    CONSTRAINT chk_integrations_secret_ref
        CHECK (secret_ref IS NULL OR (
            secret_ref = btrim(secret_ref)
            AND length(secret_ref) > 0
        )),
    CONSTRAINT chk_integrations_config_object
        CHECK (jsonb_typeof(config_json) = 'object'),
    CONSTRAINT chk_integrations_last_error_code
        CHECK (last_error_code IS NULL OR (
            last_error_code = btrim(last_error_code)
            AND length(last_error_code) > 0
        ))
);

CREATE UNIQUE INDEX uq_integrations_active_provider_workspace
    ON integrations (provider, workspace_external_id)
    WHERE workspace_external_id IS NOT NULL
      AND status <> 'DISABLED';

CREATE INDEX idx_integrations_provider_status
    ON integrations (provider, status, id);

CREATE INDEX idx_integrations_health
    ON integrations (health, updated_at DESC, id);
