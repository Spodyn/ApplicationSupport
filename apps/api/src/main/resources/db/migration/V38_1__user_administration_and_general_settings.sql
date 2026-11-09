-- Task 3: durable account actions and organization-level administration settings.

CREATE TABLE account_action_tokens (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_type varchar(24) NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_action_token_type CHECK (token_type IN ('INVITATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_action_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ux_account_action_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_account_action_tokens_active
    ON account_action_tokens (user_id, token_type, expires_at DESC)
    WHERE used_at IS NULL;

CREATE TABLE organization_settings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    organization_name varchar(200) NOT NULL DEFAULT 'Unified Support Inbox',
    interface_language varchar(16) NOT NULL DEFAULT 'pl',
    compact_mode boolean NOT NULL DEFAULT false,
    sla_warning_before_minutes integer NOT NULL DEFAULT 15 CHECK (sla_warning_before_minutes >= 0),
    sla_business_hours_only boolean NOT NULL DEFAULT true,
    admin_default_permissions text[] NOT NULL DEFAULT ARRAY[
        'manage_users', 'manage_integrations', 'manage_sla', 'manage_schedule',
        'manage_notifications', 'view_global_statistics', 'reassign_cases',
        'force_resolve', 'view_audit'
    ]::text[],
    updated_by uuid REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO organization_settings (singleton) VALUES (true);
