-- USI-71 / E05-T01: deterministic user-directory pagination and role/activity filtering.
-- Existing idx_cases_owner_status supports the active-assignment count projection.
CREATE INDEX idx_users_admin_directory_page
    ON users (created_at DESC, id DESC);

CREATE INDEX idx_users_admin_directory_role_active
    ON users (role, active, created_at DESC, id DESC);
