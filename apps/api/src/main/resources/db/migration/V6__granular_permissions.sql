CREATE TABLE permissions (
    code VARCHAR(64) PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

INSERT INTO permissions (code, description) VALUES
    ('manage_users', 'Manage user accounts and explicit permission assignments'),
    ('manage_integrations', 'Manage provider integrations and channels'),
    ('manage_sla', 'Manage SLA policies'),
    ('manage_schedule', 'Manage business hours and schedules'),
    ('manage_notifications', 'Manage notification policies'),
    ('view_global_statistics', 'View global operational statistics'),
    ('reassign_cases', 'Reassign cases between users'),
    ('force_resolve', 'Force-resolve cases as an administrative action'),
    ('view_audit', 'View the audit trail');

CREATE TABLE user_permissions (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL REFERENCES permissions(code) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, permission_code)
);

CREATE INDEX idx_user_permissions_permission_code
    ON user_permissions (permission_code, user_id);
