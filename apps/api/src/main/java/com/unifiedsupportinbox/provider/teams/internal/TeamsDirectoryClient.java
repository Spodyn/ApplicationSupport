package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.integration.ProviderChannelDiscovery.Failure;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Only a selected Graph team collection or an authenticated Connector service URL can be read. */
@Component
class TeamsDirectoryClient {
    private final TeamsAccessTokens tokens;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI graph;
    @Autowired TeamsDirectoryClient(TeamsAccessTokens tokens,ObjectMapper json) {
        this(tokens,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://graph.microsoft.com/v1.0/"));
    }
    TeamsDirectoryClient(TeamsAccessTokens tokens,ObjectMapper json,HttpClient http,URI graph) {this.tokens=tokens;this.json=json;this.http=http;this.graph=graph;}
    List<JsonNode> channels(UUID integration,UUID team) {
        String token=tokens.configurationToken(integration,"https://graph.microsoft.com/.default");
        URI collection=graph.resolve("teams/"+team+"/channels");URI next=collection;
        List<JsonNode> result=new ArrayList<>();Set<String> cursors=new HashSet<>();
        for(int page=0;page<100;page++) {
            if(!Objects.equals(next.getScheme(),collection.getScheme())||!Objects.equals(next.getRawAuthority(),collection.getRawAuthority())
                    || !next.getPath().equals(collection.getPath())||next.getUserInfo()!=null||next.getFragment()!=null||!cursors.add(next.toString()))
                throw new Failure("TEAMS_DISCOVERY_CONTINUATION_REJECTED",null);
            JsonNode root=get(next,token);
            if(!root.path("value").isArray())throw new Failure("TEAMS_DISCOVERY_INVALID_RESPONSE",Duration.ofSeconds(30));
            root.path("value").forEach(result::add);
            String continuation=root.path("@odata.nextLink").asText("");
            if(continuation.isBlank())return List.copyOf(result);
            next=URI.create(continuation);
        }
        throw new Failure("TEAMS_DISCOVERY_PAGE_LIMIT",null);
    }
    UUID resolveBotTeam(UUID integration,String serviceUrl,String botTeam) {
        if(!TeamsJwtAuthenticator.safeServiceUrl(serviceUrl))throw new Failure("TEAMS_UNTRUSTED_SERVICE_URL",null);
        String token=tokens.token(integration,"https://api.botframework.com/.default");
        JsonNode team=get(URI.create(serviceUrl.replaceAll("/+$","")+"/v3/teams/"+URLEncoder.encode(botTeam,StandardCharsets.UTF_8)),token);
        if(!botTeam.equals(team.path("id").asText()))throw new Failure("TEAMS_TEAM_IDENTITY_MISMATCH",null);
        try{return UUID.fromString(team.path("aadGroupId").asText());}
        catch(IllegalArgumentException e){throw new Failure("TEAMS_TEAM_IDENTITY_MISSING",null);}
    }
    private JsonNode get(URI uri,String token) {
        try {
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(2*1024*1024+1);
                if(response.statusCode()!=200) {
                    Duration retry=null;
                    if(response.statusCode()==429||response.statusCode()>=500) {
                        long seconds=response.headers().firstValue("Retry-After").map(v->{try{return Long.parseLong(v);}catch(NumberFormatException e){return 30L;}}).orElse(30L);
                        retry=Duration.ofSeconds(Math.max(1,Math.min(86400,seconds)));
                    }
                    throw new Failure("TEAMS_DISCOVERY_HTTP_"+response.statusCode(),retry);
                }
                if(bytes.length>2*1024*1024)throw new Failure("TEAMS_DISCOVERY_RESPONSE_TOO_LARGE",null);
                return json.readTree(bytes);
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("TEAMS_DISCOVERY_INTERRUPTED",Duration.ofSeconds(30));}
        catch(java.io.IOException e){throw new Failure("TEAMS_DISCOVERY_UNAVAILABLE",Duration.ofSeconds(30));}
    }
}
