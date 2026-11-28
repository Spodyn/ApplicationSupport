package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpSlackWebApiClient implements SlackWebApiClient {

    private static final URI DEFAULT_CHAT_POST_MESSAGE = URI.create("https://slack.com/api/chat.postMessage");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI chatPostMessageEndpoint;

    @Autowired
    HttpSlackWebApiClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), objectMapper, DEFAULT_CHAT_POST_MESSAGE);
    }

    HttpSlackWebApiClient(HttpClient httpClient, ObjectMapper objectMapper, URI chatPostMessageEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.chatPostMessageEndpoint = chatPostMessageEndpoint;
    }

    @Override
    public PostMessageResponse postMessage(
            byte[] botToken,
            String channelId,
            String threadTs,
            String text,
            MessageBodyFormat bodyFormat,
            String clientMessageId) {
        if (botToken == null || botToken.length == 0) {
            throw new IllegalArgumentException("Slack bot token is required.");
        }

        byte[] authorization = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("channel", channelId);
            payload.put("text", text);
            payload.put("mrkdwn", bodyFormat == MessageBodyFormat.MARKDOWN);
            if (threadTs != null && !threadTs.isBlank()) payload.put("thread_ts", threadTs);
            if (clientMessageId != null && !clientMessageId.isBlank()) payload.put("client_msg_id", clientMessageId);

            byte[] body = objectMapper.writeValueAsBytes(payload);
            authorization = new byte["Bearer ".length() + botToken.length];
            System.arraycopy("Bearer ".getBytes(StandardCharsets.US_ASCII), 0, authorization, 0, "Bearer ".length());
            System.arraycopy(botToken, 0, authorization, "Bearer ".length(), botToken.length);

            HttpRequest request = HttpRequest.newBuilder(chatPostMessageEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", new String(authorization, StandardCharsets.US_ASCII))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Duration retryAfter = retryAfter(response);
            int status = response.statusCode();
            if (status == 429) return new PostMessageResponse(status, false, null, "rate_limited", retryAfter);
            JsonNode json;
            try {
                json = objectMapper.readTree(response.body());
            } catch (JacksonException malformed) {
                return new PostMessageResponse(status, false, null, null, retryAfter);
            }
            boolean success = status >= 200 && status < 300 && json != null
                    && json.path("ok").isBoolean() && json.path("ok").asBoolean();
            String timestamp = success ? text(json, "ts") : null;
            String error = SlackDeliveryErrorClassifier.safePostError(text(json, "error"));
            String sender=text(json==null?null:json.path("message"),"bot_id");
            if(sender==null)sender=text(json==null?null:json.path("message"),"user");
            return new PostMessageResponse(status, success, timestamp, error, retryAfter,sender);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack Web API request could not be encoded.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack Web API call was interrupted.", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            return new PostMessageResponse(408, false, null, null, null);
        } catch (java.io.IOException exception) {
            return new PostMessageResponse(503, false, null, null, null);
        } finally {
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    @Override
    public MutationResult mutate(byte[] token,MutationCommand command) {
        var target=command.target();
        boolean edit=command.action().equals("EDIT");
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("channel",target.conversationId()); body.put("ts",target.providerMessageRef()); body.put("as_user",true);
        if(edit){body.put("text",command.body());body.put("parse",command.bodyFormat()==MessageBodyFormat.MARKDOWN?"none":"full");body.put("link_names",false);}
        try {
            var request=HttpRequest.newBuilder(chatPostMessageEndpoint.resolve(edit?"chat.update":"chat.delete"))
                    .timeout(REQUEST_TIMEOUT).header("Authorization","Bearer "+new String(token,StandardCharsets.UTF_8).strip())
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body))).build();
            var response=httpClient.send(request,HttpResponse.BodyHandlers.ofByteArray());
            int status=response.statusCode();
            if(status==429)return MutationResult.retry("SLACK_RATE_LIMITED",retryAfter(response));
            if(status>=500)return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");
            if(status==401||status==403)return MutationResult.failed("SLACK_PERMISSION_DENIED");
            if(status<200||status>=300)return MutationResult.failed("SLACK_MUTATION_REJECTED");
            if(status!=200)return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");
            JsonNode receipt;
            try{receipt=objectMapper.readTree(response.body());}
            catch(RuntimeException bad){return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");}
            if(receipt==null||!receipt.isObject()||!receipt.path("ok").isBoolean())return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");
            if(receipt.path("ok").asBoolean(false)) {
                return target.providerMessageRef().equals(text(receipt,"ts"))&&target.conversationId().equals(text(receipt,"channel"))
                        ?MutationResult.confirmed():MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");
            }
            String error=text(receipt,"error");
            if("ratelimited".equals(error)||"rate_limited".equals(error))return MutationResult.retry("SLACK_RATE_LIMITED",retryAfter(response));
            return MutationResult.failed(switch(Objects.toString(error,"")) {
                case "message_not_found"->"SLACK_MESSAGE_NOT_FOUND";
                case "cant_update_message","cant_delete_message","missing_scope","invalid_auth","token_revoked"->"SLACK_PERMISSION_DENIED";
                default->"SLACK_MUTATION_REJECTED";
            });
        }catch(java.net.ConnectException unavailable){return MutationResult.retry("SLACK_CONNECTION_UNAVAILABLE",null);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");}
        catch(java.io.IOException failure){return MutationResult.uncertain("SLACK_MUTATION_UNCONFIRMED");}
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value.trim());
                        return seconds > 0 ? java.util.Optional.of(Duration.ofSeconds(seconds)) : java.util.Optional.empty();
                    } catch (NumberFormatException exception) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(null);
    }

    private static String text(JsonNode json, String field) {
        if (json == null) return null;
        JsonNode value = json.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }
}
