-- Local, explicitly authorized sandbox only. Contains no provider credentials.
-- psql variables: teams_tenant_id, teams_context_id, teams_context_name,
-- teams_context_type (standardChannel | groupChat), teams_secret_ref.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE teams_seed_input AS SELECT
    :'teams_tenant_id'::uuid::text AS tenant,
    :'teams_context_id'::text AS context,
    :'teams_context_name'::text AS name,
    :'teams_context_type'::text AS context_type,
    :'teams_secret_ref'::text AS secret_ref;
ALTER TABLE teams_seed_input ADD CHECK(context_type IN ('standardChannel','groupChat'));
INSERT INTO customers(name,external_ref)
VALUES ('Teams sandbox customer','teams-sandbox-customer') ON CONFLICT DO NOTHING;
INSERT INTO integrations(provider,display_name,status,health,workspace_external_id,workspace_name,secret_ref)
SELECT 'TEAMS','USI Sandbox','ENABLED','UNKNOWN',tenant,'USI Sandbox',secret_ref
FROM teams_seed_input s
WHERE NOT EXISTS(SELECT 1 FROM integrations WHERE provider='TEAMS' AND workspace_external_id=s.tenant);
INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy,active,metadata_json)
SELECT i.id,s.context,s.name,c.id,'TEAMS_ROOT_REPLIES',TRUE,
    jsonb_build_object('teamsContextType',s.context_type)
FROM teams_seed_input s
JOIN integrations i ON i.provider='TEAMS' AND i.workspace_external_id=s.tenant
JOIN customers c ON c.external_ref='teams-sandbox-customer'
ON CONFLICT(integration_id,external_channel_id) DO UPDATE SET
    name=EXCLUDED.name,customer_id=EXCLUDED.customer_id,metadata_json=EXCLUDED.metadata_json;
COMMIT;
