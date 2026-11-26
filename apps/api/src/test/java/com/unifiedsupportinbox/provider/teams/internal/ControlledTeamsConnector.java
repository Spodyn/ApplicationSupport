package com.unifiedsupportinbox.provider.teams.internal;

import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Uses the real Connector serialization and receipt handling over loopback only. */
public final class ControlledTeamsConnector implements AutoCloseable {
    private final HttpServer server;
    private final URI base;
    private final List<Post> posts = new CopyOnWriteArrayList<>();
    private volatile Runnable beforeReceipt = () -> {};

    public record Post(String path, JsonNode activity) {}

    ControlledTeamsConnector(ObjectMapper json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = json.writeValueAsBytes(Map.of("access_token", "synthetic-teams-token"));
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.createContext("/emea/v3/conversations/", exchange -> {
            JsonNode activity = json.readTree(exchange.getRequestBody());
            posts.add(new Post(exchange.getRequestURI().getRawPath(), activity));
            beforeReceipt.run();
            byte[] response = json.writeValueAsBytes(Map.of("id", "synthetic-" + activity.path("id").asText()));
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Post> posts() { return List.copyOf(posts); }

    public void beforeReceipt(Runnable callback) { beforeReceipt = callback; }

    public void reset() {
        posts.clear();
        beforeReceipt = () -> {};
    }

    @Override
    public void close() { server.stop(0); }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        ControlledTeamsConnector controlledTeamsConnector(ObjectMapper json) throws IOException {
            return new ControlledTeamsConnector(json);
        }

        @Bean @Primary
        TeamsConnectorClient loopbackTeamsConnector(ControlledTeamsConnector fixture, ObjectMapper json,
                                                    TeamsOutboundAcceptance acceptance) {
            return new TeamsConnectorClient(HttpClient.newHttpClient(), json, acceptance) {
                @Override
                String token(URI endpoint, String clientId, byte[] secret) {
                    return super.token(fixture.base.resolve("/token"), clientId, secret);
                }

                @Override
                DeliveryResult send(URI endpoint, String token, TeamsRouteStore.Route route,
                                    DeliveryCommand command, List<Map<String, Object>> attachments) {
                    return super.send(fixture.base.resolve(endpoint.getRawPath()), token, route, command, attachments);
                }
            };
        }
    }
}
