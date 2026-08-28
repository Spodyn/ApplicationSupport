package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthEndpointTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exposesUnauthenticatedHealthOnly() {
        var health = restTemplate.getForEntity(url("/actuator/health"), String.class);
        var businessApi = restTemplate.getForEntity(url("/api/v1/cases"), String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
        assertThat(businessApi.getStatusCode().is4xxClientError()).isTrue();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
