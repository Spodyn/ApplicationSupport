package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthEndpointTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void exposesUnauthenticatedHealthOnly() throws IOException, InterruptedException {
        var health = get("/actuator/health");
        var businessApi = get("/api/v1/cases");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        assertThat(businessApi.statusCode()).isBetween(400, 499);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url(path))).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
