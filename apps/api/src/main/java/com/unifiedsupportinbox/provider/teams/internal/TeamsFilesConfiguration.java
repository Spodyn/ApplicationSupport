package com.unifiedsupportinbox.provider.teams.internal;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Explicit per-context storage destination; never inferred from a customer's file URL. */
@Component
class TeamsFilesConfiguration {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    TeamsFilesConfiguration(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    TeamsFilesHttpClient.FileLocation location(UUID integration,String channel) {
        var metadata=jdbc.query("""
                SELECT c.metadata_json::text FROM channels c JOIN integrations i ON i.id=c.integration_id
                WHERE c.integration_id=? AND c.external_channel_id=? AND i.provider='TEAMS'
                """,(rs,row)->json.readTree(rs.getString(1)).path("teamsFiles"),integration,channel)
                .stream().findFirst().orElse(null);
        var hosts=new HashSet<String>();
        if(metadata!=null)for(var item:metadata.path("sharePointHosts")) {
            String host=item.asText("").toLowerCase(Locale.ROOT);
            if(host.matches("[a-z0-9-]+\\.sharepoint\\.com"))hosts.add(host);
        }
        return new TeamsFilesHttpClient.FileLocation(metadata==null?null:metadata.path("driveId").asText(null),
                metadata==null?null:metadata.path("folderId").asText(null),hosts);
    }
}
