package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityProblemIntegrationTests {

    @LocalServerPort
    private int port;

    @Test
    void deniedUnauthenticatedRequestUsesTheSharedProblemJsonContract() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/not-yet-implemented"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.body()).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
        assertThat(response.body()).contains("\"correlationId\":");
        assertThat(response.body()).doesNotContain("BadCredentialsException");
    }

    @Test
    void apiResponsesCarryTheBrowserHardeningHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/not-yet-implemented"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Content-Security-Policy").orElse(""))
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
    }
}
