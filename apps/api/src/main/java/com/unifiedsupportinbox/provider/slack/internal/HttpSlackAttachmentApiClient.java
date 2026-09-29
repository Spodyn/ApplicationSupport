package com.unifiedsupportinbox.provider.slack.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpSlackAttachmentApiClient implements SlackAttachmentApiClient {

    private static final URI DEFAULT_FILE_INFO = URI.create("https://slack.com/api/files.info");
    private static final URI DEFAULT_GET_UPLOAD_URL = URI.create("https://slack.com/api/files.getUploadURLExternal");
    private static final URI DEFAULT_COMPLETE_UPLOAD = URI.create("https://slack.com/api/files.completeUploadExternal");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI fileInfoEndpoint;
    private final URI getUploadUrlEndpoint;
    private final URI completeUploadEndpoint;

    @Autowired
    HttpSlackAttachmentApiClient(ObjectMapper objectMapper) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                objectMapper,
                DEFAULT_FILE_INFO,
                DEFAULT_GET_UPLOAD_URL,
                DEFAULT_COMPLETE_UPLOAD);
    }

    HttpSlackAttachmentApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI fileInfoEndpoint,
            URI getUploadUrlEndpoint,
            URI completeUploadEndpoint) {
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.fileInfoEndpoint = java.util.Objects.requireNonNull(fileInfoEndpoint, "fileInfoEndpoint");
        this.getUploadUrlEndpoint = java.util.Objects.requireNonNull(getUploadUrlEndpoint, "getUploadUrlEndpoint");
        this.completeUploadEndpoint = java.util.Objects.requireNonNull(completeUploadEndpoint, "completeUploadEndpoint");
    }

    @Override
    public FileInfoResponse fileInfo(byte[] botToken, String providerFileId) {
        requireToken(botToken);
        String encoded = URLEncoder.encode(providerFileId, StandardCharsets.UTF_8);
        URI endpoint = URI.create(fileInfoEndpoint.toString() + (fileInfoEndpoint.getQuery() == null ? "?" : "&") + "file=" + encoded);
        ApiResponse response = sendAuthenticated(botToken, HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .GET());
        if (!response.ok()) {
            return new FileInfoResponse(
                    response.statusCode(), false, null, null, null, -1, null,
                    response.errorCode(), response.retryAfter());
        }

        JsonNode file = response.json().get("file");
        if (file == null || !file.isObject()) {
            return new FileInfoResponse(
                    response.statusCode(), false, null, null, null, -1, null,
                    "invalid_file_info", response.retryAfter());
        }
        URI downloadUri = uri(text(file, "url_private_download"));
        if (downloadUri == null) downloadUri = uri(text(file, "url_private"));
        return new FileInfoResponse(
                response.statusCode(),
                true,
                text(file, "id"),
                firstNonBlank(text(file, "name"), text(file, "title")),
                text(file, "mimetype"),
                nonNegativeLong(file, "size"),
                downloadUri,
                null,
                response.retryAfter());
    }

    @Override
    public UploadUrlResponse getUploadUrl(byte[] botToken, String filename, long sizeBytes) {
        requireToken(botToken);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filename", filename);
        payload.put("length", sizeBytes);
        ApiResponse response = sendAuthenticatedJson(botToken, getUploadUrlEndpoint, payload);
        if (!response.ok()) {
            return new UploadUrlResponse(
                    response.statusCode(), false, null, null, response.errorCode(), response.retryAfter());
        }
        return new UploadUrlResponse(
                response.statusCode(),
                true,
                text(response.json(), "file_id"),
                uri(text(response.json(), "upload_url")),
                null,
                response.retryAfter());
    }

    @Override
    public UploadBytesResponse uploadBytes(URI uploadUrl, byte[] content) {
        if (!safeSlackUploadUri(uploadUrl)) {
            throw new IllegalArgumentException("Slack upload URL was rejected.");
        }
        if (content == null) throw new IllegalArgumentException("Slack upload content is required.");
        HttpRequest request = HttpRequest.newBuilder(uploadUrl)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new UploadBytesResponse(
                    response.statusCode(),
                    response.statusCode() >= 200 && response.statusCode() < 300
                            ? null
                            : "http_" + response.statusCode(),
                    retryAfter(response));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack file upload was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Slack file upload failed.", exception);
        }
    }

    @Override
    public CompleteUploadResponse completeUpload(
            byte[] botToken,
            String providerFileId,
            String title,
            String channelId,
            String threadTs) {
        requireToken(botToken);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", providerFileId);
        if (title != null && !title.isBlank()) file.put("title", title);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("files", List.of(file));
        payload.put("channel_id", channelId);
        if (threadTs != null && !threadTs.isBlank()) payload.put("thread_ts", threadTs);

        ApiResponse response = sendAuthenticatedJson(botToken, completeUploadEndpoint, payload);
        if (!response.ok()) {
            return new CompleteUploadResponse(
                    response.statusCode(), false, providerFileId, response.errorCode(), response.retryAfter());
        }
        JsonNode files = response.json().get("files");
        String returnedId = providerFileId;
        if (files != null && files.isArray() && !files.isEmpty()) {
            String responseId = text(files.get(0), "id");
            if (responseId != null) returnedId = responseId;
        }
        return new CompleteUploadResponse(
                response.statusCode(), true, returnedId, null, response.retryAfter());
    }

    private ApiResponse sendAuthenticatedJson(byte[] botToken, URI endpoint, Map<String, Object> payload) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack file API JSON could not be generated.", exception);
        }
        return sendAuthenticated(botToken, HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private ApiResponse sendAuthenticated(byte[] botToken, HttpRequest.Builder requestBuilder) {
        requireToken(botToken);
        byte[] authorization = null;
        try {
            authorization = new byte["Bearer ".length() + botToken.length];
            System.arraycopy(
                    "Bearer ".getBytes(StandardCharsets.US_ASCII), 0,
                    authorization, 0, "Bearer ".length());
            System.arraycopy(botToken, 0, authorization, "Bearer ".length(), botToken.length);
            HttpRequest request = requestBuilder
                    .header("Authorization", new String(authorization, StandardCharsets.US_ASCII))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Duration retryAfter = retryAfter(response);
            JsonNode json = null;
            if (response.body() != null && response.body().length > 0) {
                try {
                    json = objectMapper.readTree(response.body());
                } catch (JacksonException malformed) {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return new ApiResponse(response.statusCode(), false, null, "invalid_response", retryAfter);
                    }
                }
            }
            boolean httpOk = response.statusCode() >= 200 && response.statusCode() < 300;
            boolean apiOk = json != null && json.path("ok").asBoolean(false);
            String error = text(json, "error");
            if (response.statusCode() == 429 && error == null) error = "rate_limited";
            if (!httpOk && error == null) error = "http_" + response.statusCode();
            if (httpOk && !apiOk && error == null) error = "invalid_response";
            return new ApiResponse(response.statusCode(), httpOk && apiOk, json, error, retryAfter);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack file API call was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Slack file API call failed.", exception);
        } finally {
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    private static void requireToken(byte[] token) {
        if (token == null || token.length == 0) {
            throw new IllegalArgumentException("Slack bot token is required.");
        }
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

    private static boolean safeSlackUploadUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("slack.com") || host.endsWith(".slack.com");
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        if (node == null) return -1;
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.longValue() >= 0 ? value.longValue() : -1;
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String text(JsonNode json, String field) {
        if (json == null) return null;
        JsonNode value = json.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private record ApiResponse(
            int statusCode,
            boolean ok,
            JsonNode json,
            String errorCode,
            Duration retryAfter) {
    }
}
