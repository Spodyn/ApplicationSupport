-- Additive onboarding state. Existing installation identity, secrets, channel mappings,
-- monitoring choices and conversation routes remain intact.
CREATE TABLE integration_connection_settings (
    integration_id uuid PRIMARY KEY REFERENCES integrations(id) ON DELETE CASCADE,
    automatic_discovery boolean,
    discovery_requested boolean NOT NULL DEFAULT FALSE,
    next_discovery_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retry_not_before timestamptz,
    last_discovered_at timestamptz,
    discovery_state varchar(40) NOT NULL DEFAULT 'IDLE',
    error_code varchar(128),
    discovered_channels integer NOT NULL DEFAULT 0 CHECK (discovered_channels>=0),
    lease_token uuid,
    lease_until timestamptz
);
INSERT INTO integration_connection_settings(integration_id)
    SELECT id FROM integrations WHERE provider IN ('SLACK','TEAMS');

CREATE FUNCTION initialize_connection_discovery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider IN ('SLACK','TEAMS') THEN
        INSERT INTO integration_connection_settings(integration_id,automatic_discovery,discovery_requested)
            VALUES(NEW.id,TRUE,TRUE) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER initialize_connection_discovery_trigger AFTER INSERT ON integrations
    FOR EACH ROW EXECUTE FUNCTION initialize_connection_discovery();

CREATE TABLE teams_instance_binding (
    singleton boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    integration_id uuid NOT NULL UNIQUE REFERENCES integrations(id) ON DELETE CASCADE,
    team_id uuid NOT NULL,
    bot_team_id varchar(255),
    default_customer_id uuid REFERENCES customers(id),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Infer only an unambiguous prior authenticated/configured team. Ambiguous legacy
-- installations retain all configuration but require an explicit team selection.
WITH candidates AS (
    SELECT integration_id,metadata_json->>'teamsTeamId' AS team FROM channels
    UNION
    SELECT integration_id,payload_json #>> '{channelData,team,aadGroupId}' FROM inbound_events WHERE provider='TEAMS'
), known AS (
    SELECT c.integration_id,min(c.team)::uuid AS team FROM candidates c JOIN integrations i ON i.id=c.integration_id
    WHERE i.provider='TEAMS' AND i.status<>'DISABLED'
      AND c.team ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND (SELECT count(*) FROM integrations WHERE provider='TEAMS' AND status<>'DISABLED')=1
    GROUP BY c.integration_id HAVING count(DISTINCT lower(c.team))=1
)
INSERT INTO teams_instance_binding(integration_id,team_id,default_customer_id)
SELECT k.integration_id,k.team,
    (SELECT CASE WHEN count(DISTINCT customer_id)=1 THEN min(customer_id::text)::uuid ELSE NULL END
       FROM channels WHERE integration_id=k.integration_id AND active)
FROM known k;
