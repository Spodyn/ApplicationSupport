-- Preserve administrative access when upgrading accounts created before
-- granular administration permissions became the authoritative default.

INSERT INTO user_permissions (user_id, permission_code)
SELECT u.id, permission_code
FROM users u
CROSS JOIN LATERAL unnest(ARRAY[
    'manage_users',
    'manage_integrations',
    'manage_sla',
    'manage_schedule',
    'manage_notifications',
    'view_global_statistics',
    'reassign_cases',
    'force_resolve',
    'view_audit'
]::text[]) AS permission_code
WHERE u.role = 'ADMIN'
  AND u.active = true
  AND NOT EXISTS (
      SELECT 1
      FROM user_permissions existing
      WHERE existing.user_id = u.id
  )
ON CONFLICT (user_id, permission_code) DO NOTHING;
