package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationTarget;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Normal Bot Connector posting; never Graph migration-only message posting. */
@Component
class TeamsConnectorClient {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TeamsConnectorClient.class);
    private final HttpClient http;
    private final ObjectMapper json;
    private final TeamsOutboundAcceptance acceptance;
    @org.springframework.beans.factory.annotation.Autowired
    TeamsConnectorClient(ObjectMapper json,TeamsOutboundAcceptance acceptance) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),json,acceptance);
    }
    TeamsConnectorClient(HttpClient http,ObjectMapper json,TeamsOutboundAcceptance acceptance) { this.http=http; this.json=json;this.acceptance=acceptance; }
    String token(String clientId,String tenantId,byte[] secret) {
        return token(URI.create("https://login.microsoftonline.com/"+java.util.UUID.fromString(tenantId)+"/oauth2/v2.0/token"),clientId,secret);
    }
    String token(URI endpoint,String clientId,byte[] secret) {
        return token(endpoint,clientId,secret,"https://api.botframework.com/.default");
    }
    String token(URI endpoint,String clientId,byte[] secret,String scope) {
        try {
            String form="grant_type=client_credentials&client_id="+encode(clientId)
                    +"&client_secret="+encode(new String(secret,StandardCharsets.UTF_8).strip())
                    +"&scope="+encode(scope);
            var response=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==429 || response.statusCode()>=500) throw new TokenUnavailable(true);
            if (response.statusCode()!=200) throw new TokenUnavailable(false);
            String token=json.readTree(response.body()).path("access_token").asText("");
            if (token.isBlank()) throw new TokenUnavailable(false);
            return token;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new TokenUnavailable(true); }
        catch (java.io.IOException failure) { throw new TokenUnavailable(true); }
    }
    DeliveryResult send(String token, TeamsRouteStore.Route route, DeliveryCommand command) {
        return send(token,route,command,java.util.List.of());
    }
    DeliveryResult send(String token, TeamsRouteStore.Route route, DeliveryCommand command,java.util.List<Map<String,Object>> attachments) {
        if (!TeamsJwtAuthenticator.safeServiceUrl(route.serviceUrl())) return DeliveryResult.permanentFailure("TEAMS_UNTRUSTED_SERVICE_URL");
        String base=route.serviceUrl().replaceAll("/+$", "");
        String path="/v3/conversations/"+encode(route.conversationId())+"/activities";
        if (command.externalThreadKey()!=null) path+="/"+encode(command.externalThreadKey());
        return send(URI.create(base+path),token,route,command,attachments);
    }
    DeliveryResult send(URI endpoint,String token,TeamsRouteStore.Route route,DeliveryCommand command) {
        return send(endpoint,token,route,command,java.util.List.of());
    }
    DeliveryResult send(URI endpoint,String token,TeamsRouteStore.Route route,DeliveryCommand command,java.util.List<Map<String,Object>> attachments) {
        try {
            var body=new java.util.LinkedHashMap<String,Object>();
            body.put("type","message"); body.put("id",command.messageId().toString());
            body.put("from",Map.of("id",route.botId())); body.put("conversation",Map.of("id",route.conversationId()));
            body.put("text",command.body()); body.put("textFormat",command.bodyFormat()==com.unifiedsupportinbox.messaging.MessageBodyFormat.MARKDOWN ? "markdown" : "plain");
            if (command.externalThreadKey()!=null) body.put("replyToId",command.externalThreadKey());
            if(!attachments.isEmpty())body.put("attachments",attachments);
            var response=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString());
            int status=response.statusCode();
            if (status==429) return DeliveryResult.transientFailure("TEAMS_RATE_LIMITED",retryAfter(response));
            if (status>=500) return DeliveryResult.transientFailure("TEAMS_PROVIDER_UNAVAILABLE",retryAfter(response));
            if (status<200 || status>=300) return DeliveryResult.permanentFailure(switch(status) {
                case 401 -> "TEAMS_AUTH_FAILED"; case 403 -> "TEAMS_PERMISSION_DENIED"; case 404 -> "TEAMS_CONVERSATION_NOT_FOUND"; default -> "TEAMS_MESSAGE_REJECTED";
            });
            if(status==202 && response.body().isBlank()) {
                return acceptedAsynchronously(command);
            }
            try {
                var receipt=json.readTree(response.body());
                String reference=receipt.path("id").asText("");
                if (reference.isBlank()) {
                    if(status==202)return acceptedAsynchronously(command);
                    LOG.warn("Teams outbound acceptance reference missing; status={}, receiptType={}",status,receipt.getNodeType());return DeliveryResult.permanentFailure("TEAMS_DELIVERY_UNCONFIRMED");
                }
                command.recordAcceptedBody().accept(reference);
                try {
                    command.recordMutationTarget().accept(new MutationTarget(reference,route.conversationId(),route.botId(),route.serviceUrl(),
                            route.contextType(),json.writeValueAsString(attachments),null));
                }catch(RuntimeException unavailable) {
                    LOG.warn("Teams mutation route checkpoint unavailable; messageId={}",command.messageId());
                }
                return DeliveryResult.sent(reference);
            }catch(RuntimeException failure) {
                if(status==202)return acceptedAsynchronously(command);
                // A successful HTTP send must not be replayed because receipt parsing or
                // the local checkpoint failed after the customer-visible side effect.
                LOG.warn("Teams outbound receipt unconfirmed; reason={}",failure.getClass().getSimpleName());
                return DeliveryResult.permanentFailure("TEAMS_DELIVERY_UNCONFIRMED");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return DeliveryResult.permanentFailure("TEAMS_DELIVERY_UNCONFIRMED");
        } catch (java.io.IOException failure) {
            LOG.warn("Teams outbound outcome unconfirmed; reason={}",failure.getClass().getSimpleName());
            // Connector has no documented idempotency key: don't automatically replay an ambiguous send.
            return DeliveryResult.permanentFailure("TEAMS_DELIVERY_UNCONFIRMED");
        }
    }
    MutationResult mutate(String token,MutationCommand command) {
        var target=command.target();
        if(!TeamsJwtAuthenticator.safeServiceUrl(target.serviceUrl()))return MutationResult.failed("TEAMS_UNTRUSTED_SERVICE_URL");
        String path=target.serviceUrl().replaceAll("/+$","")+"/v3/conversations/"+encode(target.conversationId())+"/activities/"+encode(target.providerMessageRef());
        return mutate(URI.create(path),token,command);
    }

    MutationResult mutate(URI endpoint,String token,MutationCommand command) {
        try {
            var target=command.target();
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json");
            if(command.action().equals("DELETE"))request.DELETE();
            else {
                var body=new java.util.LinkedHashMap<String,Object>();
                body.put("type","message");body.put("id",target.providerMessageRef());
                body.put("from",Map.of("id",target.senderId()));body.put("conversation",Map.of("id",target.conversationId()));
                body.put("text",command.body());body.put("textFormat",command.bodyFormat()==com.unifiedsupportinbox.messaging.MessageBodyFormat.MARKDOWN?"markdown":"plain");
                var attachments=json.readTree(target.attachmentPayload());
                if(attachments!=null&&attachments.isArray()&&!attachments.isEmpty())body.put("attachments",attachments);
                request.PUT(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            int status=response.statusCode();
            if(status==429)return MutationResult.retry("TEAMS_RATE_LIMITED",retryAfter(response));
            if(status==202||status>=500)return MutationResult.uncertain("TEAMS_MUTATION_UNCONFIRMED");
            if(status==401||status==403)return MutationResult.failed("TEAMS_PERMISSION_DENIED");
            if(status==404)return MutationResult.failed("TEAMS_MESSAGE_NOT_FOUND");
            if(status<200||status>=300)return MutationResult.failed("TEAMS_MUTATION_REJECTED");
            return MutationResult.confirmed();
        }catch(java.net.ConnectException unavailable){return MutationResult.retry("TEAMS_CONNECTION_UNAVAILABLE",null);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return MutationResult.uncertain("TEAMS_MUTATION_UNCONFIRMED");}
        catch(java.io.IOException failure){return MutationResult.uncertain("TEAMS_MUTATION_UNCONFIRMED");}
    }

    private DeliveryResult acceptedAsynchronously(DeliveryCommand command) {
        try {acceptance.record(command);return DeliveryResult.sent(null);}
        catch(RuntimeException failure) {
            // The request was accepted: a failed local checkpoint must never cause a replay.
            LOG.warn("Teams asynchronous acceptance checkpoint unavailable");
            return DeliveryResult.permanentFailure("TEAMS_DELIVERY_UNCONFIRMED");
        }
    }
    private static Duration retryAfter(HttpResponse<?> response) {
        try { return Duration.ofSeconds(Math.max(1,Long.parseLong(response.headers().firstValue("Retry-After").orElse("5")))); }
        catch (NumberFormatException malformed) { return Duration.ofSeconds(5); }
    }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    static class TokenUnavailable extends RuntimeException {
        final boolean retryable;
        TokenUnavailable(boolean retryable) { super("Teams token acquisition failed."); this.retryable=retryable; }
    }
}
