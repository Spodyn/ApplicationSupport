package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@Component
class TeamsHistoryRecoveryAdapter implements HistoryRecoveryAdapter {
    private com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections;
    @org.springframework.beans.factory.annotation.Autowired
    void connectionConfiguration(com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections){this.connections=connections;}
    private final TeamsAccessTokens tokens;
    private final TeamsInboundDeliveryService inbound;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI origin;
    @Autowired
    TeamsHistoryRecoveryAdapter(TeamsAccessTokens tokens,TeamsInboundDeliveryService inbound,JdbcTemplate jdbc,ObjectMapper json) {
        this(tokens,inbound,jdbc,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://graph.microsoft.com/v1.0/"));
    }
    TeamsHistoryRecoveryAdapter(TeamsAccessTokens tokens,TeamsInboundDeliveryService inbound,JdbcTemplate jdbc,ObjectMapper json,HttpClient http,URI origin) {
        this.tokens=tokens;this.inbound=inbound;this.jdbc=jdbc;this.json=json;this.http=http;this.origin=origin;
    }
    public String provider(){return "TEAMS";}
    public Page fetch(Task task) {
        String context=task.metadata().path("teamsContextType").asText("");
        if(!Set.of("standardChannel","groupChat").contains(context))throw new Unavailable("TEAMS_UNSUPPORTED_HISTORY_CONTEXT",null);
        if(connections!=null) {
            var scope=connections.teamScope(task.integrationId()).orElseThrow(()->new Unavailable("TEAMS_TEAM_SELECTION_REQUIRED",null));
            if(!context.equals("standardChannel") || !scope.teamId().toString().equals(team(task)))throw new Unavailable("TEAMS_TEAM_NOT_SELECTED",null);
        }
        String path=basePath(task,context);
        if(!task.thread().isEmpty())path+="/"+enc(task.thread())+"/replies";
        URI target=task.cursor()==null?origin.resolve(path+"?$top=50"):URI.create(task.cursor());
        // Treat continuation URLs as untrusted input; do not forward bearer tokens outside this exact collection.
        if(!Objects.equals(target.getScheme(),origin.getScheme())||!Objects.equals(target.getRawAuthority(),origin.getRawAuthority())
                ||!target.getRawPath().equals(origin.resolve(path).getRawPath()) || target.getUserInfo()!=null || target.getFragment()!=null)
            throw new Unavailable("TEAMS_HISTORY_CONTINUATION_REJECTED",null);
        try {
            String token=tokens.token(task.integrationId(),"https://graph.microsoft.com/.default");
            var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(task.cursor()!=null && (response.statusCode()==400 || response.statusCode()==410))
                return fetch(task.withoutCursor());
            if(response.statusCode()!=200) {
                Duration retry=null;
                if(response.statusCode()==429||response.statusCode()>=500) {
                    long seconds=response.headers().firstValue("Retry-After").map(v->{try{return Long.parseLong(v);}catch(NumberFormatException e){return 30L;}}).orElse(30L);
                    retry=Duration.ofSeconds(Math.max(1,seconds));
                }
                throw new Unavailable("TEAMS_HISTORY_HTTP_"+response.statusCode(),retry);
            }
            JsonNode root=json.readTree(response.body());
            if(!root.path("value").isArray())throw new Unavailable("TEAMS_HISTORY_INVALID_RESPONSE",Duration.ofSeconds(30));
            List<JsonNode> messages=new ArrayList<>();List<String> threads=new ArrayList<>();
            for(var message:root.path("value")) {
                String id=message.path("id").asText("");
                if(id.isBlank())continue;
                if(context.equals("standardChannel")&&task.thread().isEmpty())threads.add(id);
                if(message.path("deletedDateTime").isTextual() || !message.path("from").path("user").path("id").isTextual())continue;
                if(task.includes(Instant.parse(message.path("createdDateTime").asText())))messages.add(message);
            }
            String next=root.path("@odata.nextLink").asText(null);
            return new Page(messages,threads,next==null||next.isBlank()?null:next,root.path("value").size());
        } catch(ProviderAttachmentException failure) {
            throw new Unavailable(failure.errorCode(),failure.retryable()?Duration.ofSeconds(30):null);
        } catch(java.io.IOException failure) {throw new Unavailable("TEAMS_HISTORY_UNAVAILABLE",Duration.ofSeconds(30));}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new Unavailable("TEAMS_HISTORY_INTERRUPTED",Duration.ofSeconds(30));}
    }
    private String team(Task task) {
        String team=task.metadata().path("teamsTeamId").asText("");
        if(team.isBlank()) team=jdbc.query("""
                SELECT payload_json #>> '{channelData,team,aadGroupId}' FROM inbound_events
                WHERE integration_id=? AND payload_json #>> '{channelData,channel,id}'=?
                  AND payload_json #>> '{channelData,team,aadGroupId}' IS NOT NULL
                ORDER BY received_at DESC LIMIT 1
                """,(rs,n)->rs.getString(1),task.integrationId(),task.externalChannelId()).stream().findFirst().orElse("");
        try {
            String canonical=UUID.fromString(team).toString();
            jdbc.update("UPDATE channels SET metadata_json=jsonb_set(metadata_json,'{teamsTeamId}',to_jsonb(?::text)) WHERE id=?",canonical,task.channelId());
            return canonical;
        } catch(IllegalArgumentException e) {throw new Unavailable("TEAMS_HISTORY_TEAM_CONTEXT_MISSING",null);}
    }
    private String basePath(Task task,String context) {
        return context.equals("groupChat")?"chats/"+enc(task.externalChannelId())+"/messages":
                "teams/"+team(task)+"/channels/"+enc(task.externalChannelId())+"/messages";
    }
    public UUID enqueue(Task task,JsonNode message) {
        String context=task.metadata().path("teamsContextType").asText();
        var routes=jdbc.query("SELECT * FROM teams_conversation_routes WHERE integration_id=? AND external_channel_id=? ORDER BY updated_at DESC LIMIT 1",
                (rs,n)->new TeamsRouteStore.Route(rs.getString("connector_conversation_id"),rs.getString("service_url"),rs.getString("bot_id"),rs.getString("context_type")),task.integrationId(),task.externalChannelId());
        if(routes.isEmpty())throw new Unavailable("TEAMS_HISTORY_BOT_ROUTE_MISSING",null);
        var route=routes.getFirst();String id=message.path("id").asText();
        String thread=task.thread().isEmpty()?id:task.thread();
        ObjectNode activity=json.createObjectNode().put("type","message").put("id",id).put("channelId","msteams")
                .put("timestamp",message.path("createdDateTime").asText()).put("serviceUrl",route.serviceUrl())
                .put("text",message.path("body").path("content").asText("")).put("textFormat","xml");
        activity.putObject("from").put("id",message.path("from").path("user").path("id").asText())
                .put("name",message.path("from").path("user").path("displayName").asText());
        activity.putObject("recipient").put("id",route.botId());
        activity.putObject("conversation").put("id",context.equals("groupChat")?task.externalChannelId():task.externalChannelId()+";messageid="+thread)
                .put("conversationType",context.equals("groupChat")?"groupChat":"channel");
        if(context.equals("standardChannel")) {
            var data=activity.putObject("channelData");data.putObject("channel").put("id",task.externalChannelId()).put("membershipType","standard");
            data.putObject("team").put("aadGroupId",team(task));data.putObject("replyChain").put("id",thread);
        }
        var attachments=activity.putArray("attachments");
        for(var attachment:message.path("attachments"))attachments.add(attachment);
        if(message.path("body").path("content").asText("").contains("hostedContents"))attachments.addObject().put("contentType","text/html");
        String key="resync:"+UUID.nameUUIDFromBytes((task.id()+":"+id).getBytes(StandardCharsets.UTF_8));
        return inbound.persistAndWake(task.integrationId(),key,json.writeValueAsString(activity),"resync:"+task.runId()).id();
    }
    private static String enc(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20");}
}
