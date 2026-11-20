package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Extends the existing Slack history client and durable inbound/attachment pipeline. */
@Component
class SlackHistoryRecoveryAdapter implements HistoryRecoveryAdapter {
    private final SlackHistoryClient client;
    private final SlackAdministrationApiClient authentication;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackInboundDeliveryService inbound;
    private final ObjectMapper json;
    SlackHistoryRecoveryAdapter(SlackHistoryClient client, SlackAdministrationApiClient authentication, ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets, SlackInboundDeliveryService inbound, ObjectMapper json) {
        this.client=client;this.authentication=authentication;this.integrations=integrations;this.secrets=secrets;this.inbound=inbound;this.json=json;
    }
    public String provider() { return "SLACK"; }
    public Page fetch(Task task) {
        var credential=integrations.findEnabledForProvider(IntegrationProvider.SLACK).stream()
                .filter(c->c.integrationId().equals(task.integrationId())).findFirst()
                .orElseThrow(()->new Unavailable("SLACK_INTEGRATION_DISABLED",null));
        // Channel thread reads may require a user history token. Never substitute broader UI credentials.
        byte[] token=secrets.resolve(credential.secretRef(),"slack-history-token")
                .or(()->secrets.resolve(credential.secretRef(),SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .orElseThrow(()->new Unavailable("SLACK_HISTORY_TOKEN_MISSING",null));
        try {
            var identity=authentication.testAuthentication(token);
            if(identity==null)throw new Unavailable("SLACK_HISTORY_AUTH_UNAVAILABLE",Duration.ofSeconds(30));
            if(identity.statusCode()==429)throw new Unavailable("SLACK_RATE_LIMITED",Duration.ofMinutes(1));
            if(identity.statusCode()<200 || identity.statusCode()>=300)
                throw new Unavailable("SLACK_HISTORY_AUTH_HTTP_"+identity.statusCode(),
                        SlackDeliveryErrorClassifier.isTransientHttpStatus(identity.statusCode())?Duration.ofSeconds(30):null);
            if(!identity.ok())throw new Unavailable(SlackDeliveryErrorClassifier.normalizedApiError(identity.errorCode()),
                    SlackDeliveryErrorClassifier.isTransientApiError(identity.errorCode())?Duration.ofSeconds(30):null);
            if(credential.workspaceExternalId()==null || !credential.workspaceExternalId().equals(identity.workspaceId()))
                throw new Unavailable("SLACK_HISTORY_WORKSPACE_MISMATCH",null);
            var page=task.thread().isEmpty()
                    ? client.history(token,task.externalChannelId(),task.cursor(),null,ts(task.until()),15)
                    : client.replies(token,task.externalChannelId(),task.thread(),task.cursor(),ts(task.from()),ts(task.until()),15);
            if (!page.ok() && "invalid_cursor".equals(page.errorCode()) && task.cursor()!=null) {
                // Provider cursors can expire during an outage. Replay this collection's captured
                // range; the shared message identity and durable thread keys suppress duplicates.
                return fetch(task.withoutCursor());
            }
            if(page.statusCode()==429) throw new Unavailable("SLACK_RATE_LIMITED",positive(page.retryAfter(),Duration.ofMinutes(1)));
            if(page.statusCode()<200||page.statusCode()>=300) throw new Unavailable("SLACK_HTTP_"+page.statusCode(),
                    SlackDeliveryErrorClassifier.isTransientHttpStatus(page.statusCode())?positive(page.retryAfter(),Duration.ofSeconds(30)):null);
            if(!page.ok()) throw new Unavailable(SlackDeliveryErrorClassifier.normalizedApiError(page.errorCode()),
                    SlackDeliveryErrorClassifier.isTransientApiError(page.errorCode())?Duration.ofSeconds(30):null);
            List<JsonNode> messages=new ArrayList<>();List<String> threads=new ArrayList<>();
            for(var message:page.messages()) {
                String id=message.path("ts").asText("");
                if(id.isBlank())continue;
                // Scan all roots, even before the requested window: an old thread can have new replies.
                if(task.thread().isEmpty() && message.path("reply_count").asInt(0)>0) threads.add(id);
                if(task.includes(timestamp(id))) messages.add(message);
            }
            String cursor=page.nextCursor();
            return new Page(messages,threads,cursor==null||cursor.isBlank()?null:cursor,page.messages().size());
        } finally { Arrays.fill(token,(byte)0); }
    }
    public UUID enqueue(Task task,JsonNode message) {
        String key="resync:"+UUID.nameUUIDFromBytes((task.id()+":"+message.path("ts").asText()).getBytes(StandardCharsets.UTF_8));
        ObjectNode event=((ObjectNode)message).deepCopy();
        event.put("type","message");event.put("channel",task.externalChannelId());
        event.put("channel_type",task.externalChannelId().startsWith("G")?"group":"channel");
        ObjectNode callback=json.createObjectNode().put("type","event_callback").put("event_id",key);
        callback.set("event",event);
        return inbound.persistAndWake(task.integrationId(),key,json.writeValueAsString(callback),"resync:"+task.runId()).id();
    }
    static String ts(Instant value) { return value==null?null:value.getEpochSecond()+"."+String.format(Locale.ROOT,"%09d",value.getNano()); }
    static Instant timestamp(String ts) {
        var decimal=new java.math.BigDecimal(ts);long seconds=decimal.longValue();
        return Instant.ofEpochSecond(seconds,decimal.subtract(java.math.BigDecimal.valueOf(seconds)).movePointRight(9).longValue());
    }
    private static Duration positive(Duration value,Duration fallback) {return value==null||value.isZero()||value.isNegative()?fallback:value;}
}
