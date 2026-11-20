package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.integration.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ConnectionConfigurationStore implements ProviderConnectionConfiguration {
    private final JdbcTemplate jdbc;
    private final boolean legacySlackAutomatic;
    private final com.unifiedsupportinbox.audit.AuditEventStore audit;
    ConnectionConfigurationStore(JdbcTemplate jdbc,@Value("${usi.slack.channel-discovery.enabled:false}") boolean legacySlackAutomatic,com.unifiedsupportinbox.audit.AuditEventStore audit) {
        this.jdbc=jdbc;this.legacySlackAutomatic=legacySlackAutomatic;this.audit=audit;
    }
    public Settings settings(UUID integration) {
        return jdbc.query("""
                SELECT s.*,COALESCE(s.automatic_discovery,i.provider='SLACK' AND ?) AS automatic
                FROM integration_connection_settings s JOIN integrations i ON i.id=s.integration_id WHERE s.integration_id=?
                """,(rs,n)->new Settings(integration,rs.getBoolean("automatic"),rs.getString("discovery_state"),
                instant(rs,"last_discovered_at"),instant(rs,"next_discovery_at"),rs.getString("error_code"),rs.getInt("discovered_channels")),legacySlackAutomatic,integration)
                .stream().findFirst().orElseThrow(()->ApiProblemException.notFound("Provider connection was not found."));
    }
    @Transactional public Settings configure(UUID integration,boolean automatic) {
        if(jdbc.update("UPDATE integration_connection_settings SET automatic_discovery=?,discovery_requested=discovery_requested OR ? WHERE integration_id=?",automatic,automatic,integration)!=1)
            throw ApiProblemException.notFound("Provider connection was not found.");
        return settings(integration);
    }
    public Optional<TeamScope> teamScope(UUID integration) {
        return jdbc.query("SELECT * FROM teams_instance_binding WHERE integration_id=?",(rs,n)->new TeamScope(integration,
                rs.getObject("team_id",UUID.class),rs.getString("bot_team_id"),rs.getObject("default_customer_id",UUID.class)),integration).stream().findFirst();
    }
    @Transactional public void selectTeam(UUID integration,UUID team,UUID customer) {
        jdbc.query("SELECT pg_advisory_xact_lock(1785219055)",rs->{});
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customers WHERE id=? AND active)",Boolean.class,customer)))
            throw ApiProblemException.validationFailed("Choose an active default customer.");
        jdbc.query("SELECT integration_id FROM integration_connection_settings WHERE integration_id=? FOR UPDATE",rs->{},integration);
        var bound=jdbc.query("SELECT integration_id FROM teams_instance_binding FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class));
        if(!bound.isEmpty()&&!bound.getFirst().equals(integration))throw ApiProblemException.conflict("This instance already has a selected Teams connection. Edit that connection.");
        jdbc.update("""
                INSERT INTO teams_instance_binding(integration_id,team_id,default_customer_id) VALUES(?,?,?)
                ON CONFLICT(singleton) DO UPDATE SET team_id=EXCLUDED.team_id,default_customer_id=EXCLUDED.default_customer_id,
                  bot_team_id=CASE WHEN teams_instance_binding.team_id=EXCLUDED.team_id THEN teams_instance_binding.bot_team_id ELSE NULL END,
                  updated_at=CURRENT_TIMESTAMP
                """,integration,team,customer);
        requestDiscovery(integration);
    }
    public void verifyBotTeam(UUID integration,UUID team,String botTeam) {
        if(jdbc.update("UPDATE teams_instance_binding SET bot_team_id=?,updated_at=CURRENT_TIMESTAMP WHERE integration_id=? AND team_id=?",botTeam,integration,team)!=1)
            throw ApiProblemException.authenticationRequired();
    }
    @Transactional public void adoptConfiguredTeamsTenant(UUID integration,String tenant) {
        String canonical=UUID.fromString(tenant).toString();
        jdbc.query("SELECT integration_id FROM integration_connection_settings WHERE integration_id=? FOR UPDATE",rs->{},integration);
        var scope=teamScope(integration);if(scope.isEmpty())return;
        lockTeam(integration,scope.get().teamId());
        // Only a draft saved before VPS setup may adopt the server identity. Never
        // retarget an existing configured tenant or silently enable a connection.
        if(jdbc.update("UPDATE integrations SET workspace_external_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND provider='TEAMS' AND status='CONFIGURING' AND workspace_external_id='teams-unconfigured'",canonical,integration)==1)
            audit.append(com.unifiedsupportinbox.audit.AuditActorType.SYSTEM,null,"TEAMS_SERVER_IDENTITY_BOUND","integration",integration,null,Map.of("tenantId",canonical));
    }
    @Transactional public void lockTeam(UUID integration,UUID team) {
        if(jdbc.query("SELECT integration_id FROM teams_instance_binding WHERE integration_id=? AND team_id=? FOR SHARE",(rs,n)->rs.getObject(1,UUID.class),integration,team).isEmpty())
            throw ApiProblemException.authenticationRequired();
    }
    public void requestDiscovery(UUID integration) {
        jdbc.update("UPDATE integration_connection_settings SET discovery_requested=TRUE,discovery_state=CASE WHEN lease_until>CURRENT_TIMESTAMP THEN discovery_state ELSE 'DISCOVERING' END WHERE integration_id=?",integration);
    }
    @Transactional public Optional<Claim> claimDue() {
        return jdbc.query("""
                WITH due AS (
                  SELECT s.integration_id FROM integration_connection_settings s JOIN integrations i ON i.id=s.integration_id
                  WHERE i.status<>'DISABLED' AND (s.discovery_requested OR
                    (s.lease_token IS NOT NULL AND s.lease_until<=CURRENT_TIMESTAMP) OR (
                    COALESCE(s.automatic_discovery,i.provider='SLACK' AND ?) AND s.next_discovery_at<=CURRENT_TIMESTAMP))
                    AND (s.lease_until IS NULL OR s.lease_until<=CURRENT_TIMESTAMP)
                    AND (s.retry_not_before IS NULL OR s.retry_not_before<=CURRENT_TIMESTAMP)
                  ORDER BY s.next_discovery_at,s.integration_id FOR UPDATE OF s SKIP LOCKED LIMIT 1
                ), claimed AS (
                  UPDATE integration_connection_settings s SET lease_token=uuidv7(),lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes',
                    discovery_requested=FALSE,discovery_state='DISCOVERING' FROM due WHERE s.integration_id=due.integration_id RETURNING s.*
                ) SELECT s.*,i.provider FROM claimed s JOIN integrations i ON i.id=s.integration_id
                """,(rs,n)->new Claim(rs.getObject("integration_id",UUID.class),IntegrationProvider.valueOf(rs.getString("provider")),rs.getObject("lease_token",UUID.class)),legacySlackAutomatic).stream().findFirst();
    }
    public void finished(Claim claim,String state,String error,int count,Duration retry) {
        String safe=error==null?null:error.matches("[A-Z][A-Z0-9_]{0,127}")?error:"DISCOVERY_FAILED";
        jdbc.update("""
                UPDATE integration_connection_settings SET discovery_state=?,error_code=?,discovered_channels=?,
                  last_discovered_at=CASE WHEN ?='READY' THEN CURRENT_TIMESTAMP ELSE last_discovered_at END,
                  next_discovery_at=CURRENT_TIMESTAMP+?*INTERVAL '1 second',
                  discovery_requested=discovery_requested OR ?,
                  retry_not_before=CASE WHEN ? THEN CURRENT_TIMESTAMP+?*INTERVAL '1 second' ELSE NULL END,
                  lease_token=NULL,lease_until=NULL
                WHERE integration_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP
                """,state,safe,count,state,retry==null?30:Math.max(1,retry.toSeconds()),error!=null&&retry!=null,
                error!=null&&retry!=null,retry==null?30:Math.max(1,retry.toSeconds()),claim.integrationId(),claim.lease());
    }
    public boolean renew(Claim claim) {
        return jdbc.update("UPDATE integration_connection_settings SET lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes' WHERE integration_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",claim.integrationId(),claim.lease())==1;
    }
    @Transactional public void lockDiscovery(Claim claim,UUID team) {
        if(jdbc.query("""
                SELECT s.integration_id FROM integration_connection_settings s JOIN integrations i ON i.id=s.integration_id
                WHERE s.integration_id=? AND s.lease_token=? AND s.lease_until>CURRENT_TIMESTAMP AND i.status<>'DISABLED' FOR UPDATE OF s
                """,(rs,n)->rs.getObject(1,UUID.class),claim.integrationId(),claim.lease()).isEmpty())
            throw ApiProblemException.conflict("Discovery ownership changed; this snapshot was discarded.");
        if(team!=null)lockTeam(claim.integrationId(),team);
    }
    private static Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException {
        var value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();
    }
}
