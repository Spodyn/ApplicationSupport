package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.integration.ProviderChannelDiscovery.Failure;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SlackDiscoveryRateLimitTests {
    @Test void rateLimitedDiscoveryCarriesRetryAfterToTheDurableScheduler()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getResponseHeaders().set("Retry-After","120");exchange.sendResponseHeaders(429,-1);exchange.close();});server.start();
        try {
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api");
            var client=new HttpSlackAdministrationApiClient(HttpClient.newHttpClient(),new ObjectMapper(),endpoint,endpoint);
            assertThatThrownBy(()->client.listConversations("synthetic-token".getBytes(java.nio.charset.StandardCharsets.UTF_8),"team",null))
                    .isInstanceOfSatisfying(Failure.class,e->{assertThat(e.code).isEqualTo("SLACK_DISCOVERY_HTTP_429");assertThat(e.retryAfter).isEqualTo(Duration.ofSeconds(120));});
        } finally {server.stop(0);}
    }
}
