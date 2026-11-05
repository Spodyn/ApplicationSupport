package com.unifiedsupportinbox.provider.slack.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpSlackAdministrationApiClient implements SlackAdministrationApiClient {

    private static final URI AUTH_TEST_ENDPOINT = URI.create("https://slack.com/api/auth.test");
    private static final URI CONVERSATIONS_ENDPOINT = URI.create("https://slack.com/api/conversations.list");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI authTestEndpoint;
    private final URI conversationsEndpoint;

    @Autowired
    HttpSlackAdministrationApiClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper, AUTH_TEST_ENDPOINT, CONVERSATIONS_ENDPOINT);
    }

    HttpSlackAdministrationApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI authTestEndpoint,
            URI conversationsEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authTestEndpoint = authTestEndpoint;
        this.conversationsEndpoint = conversationsEndpoint;
    }

    @Override
    public AuthResponse testAuthentication(byte[] botToken) {
        HttpResponse<byte[]> response = send(botToken, HttpRequest.newBuilder(authTestEndpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.noBody()));
        JsonNode json = json(response);
        return new AuthResponse(
                response.statusCode(),
                success(response, json),
                text(json, "team_id"),
                text(json, "team"),
                error(response, json));
    }

    @Override
    public ConversationsResponse listConversations(byte[] botToken, String workspaceId, String cursor) {
        StringBuilder query = new StringBuilder("?types=public_channel%2Cprivate_channel&exclude_archived=true&limit=200");
        if (workspaceId != null && !workspaceId.isBlank()) {
            query.append("&team_id=").append(encoded(workspaceId));
        }
        if (cursor != null && !cursor.isBlank()) {
            query.append("&cursor=").append(encoded(cursor));
        }
        URI endpoint = URI.create(conversationsEndpoint + query.toString());
        HttpResponse<byte[]> response = send(botToken, HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .GET());
        JsonNode json = json(response);
        List<Conversation> channels = new ArrayList<>();
        JsonNode values = json == null ? null : json.get("channels");
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String id = text(value, "id");
                String name = text(value, "name");
                if (id == null || name == null) continue;
                channels.add(new Conversation(
                        id,
                        name,
                        value.path("is_member").asBoolean(false),
                        value.path("is_private").asBoolean(false),
                        value.path("is_ext_shared").asBoolean(false)));
            }
        }
        JsonNode metadata = json == null ? null : json.get("response_metadata");
        return new ConversationsResponse(
                response.statusCode(),
                success(response, json),
                List.copyOf(channels),
                text(metadata, "next_cursor"),
                error(response, json));
    }

    private HttpResponse<byte[]> send(byte[] botToken, HttpRequest.Builder builder) {
        if (botToken == null || botToken.length == 0) {
            throw new IllegalArgumentException("Slack bot token is required.");
        }
        byte[] authorization = new byte["Bearer ".length() + botToken.length];
        try {
            System.arraycopy("Bearer ".getBytes(StandardCharsets.US_ASCII), 0, authorization, 0, "Bearer ".length());
            System.arraycopy(botToken, 0, authorization, "Bearer ".length(), botToken.length);
            HttpRequest request = builder
                    .header("Authorization", new String(authorization, StandardCharsets.US_ASCII))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack administration API call was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Slack administration API call failed.", exception);
        } finally {
            Arrays.fill(authorization, (byte) 0);
        }
    }

    private JsonNode json(HttpResponse<byte[]> response) {
        if (response.body() == null || response.body().length == 0) return null;
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack administration API returned invalid JSON.", exception);
        }
    }

    private static boolean success(HttpResponse<?> response, JsonNode json) {
        return response.statusCode() >= 200
                && response.statusCode() < 300
                && json != null
                && json.path("ok").asBoolean(false);
    }

    private static String error(HttpResponse<?> response, JsonNode json) {
        String error = text(json, "error");
        return error != null ? error : "http_" + response.statusCode();
    }

    private static String text(JsonNode json, String field) {
        if (json == null) return null;
        JsonNode value = json.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
