package com.unifiedsupportinbox.provider.teams.internal;

import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationResult;
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
    private final List<Map<String,Object>> mutations = new CopyOnWriteArrayList<>();
    private final Map<String,JsonNode> activities = new java.util.concurrent.ConcurrentHashMap<>();
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
            if (!exchange.getRequestMethod().equals("POST")) {
                String path=exchange.getRequestURI().getRawPath();
                String id=java.net.URLDecoder.decode(path.substring(path.lastIndexOf('/')+1),java.nio.charset.StandardCharsets.UTF_8);
                JsonNode activity=exchange.getRequestMethod().equals("PUT")?json.readTree(exchange.getRequestBody()):json.createObjectNode();
                boolean exists=activities.containsKey(id);
                if(exists) {
                    if(exchange.getRequestMethod().equals("PUT"))activities.put(id,activity); else activities.remove(id);
                    mutations.add(Map.of("method",exchange.getRequestMethod(),"id",id,"path",path,"activity",activity));
                }
                byte[] response=json.writeValueAsBytes(Map.of("id",id));exchange.sendResponseHeaders(exists?200:404,response.length);
                try(var output=exchange.getResponseBody()){output.write(response);}return;
            }
            JsonNode activity = json.readTree(exchange.getRequestBody());
            posts.add(new Post(exchange.getRequestURI().getRawPath(), activity));
            activities.put("synthetic-"+activity.path("id").asText(),activity);
            beforeReceipt.run();
            byte[] response = json.writeValueAsBytes(Map.of("id", "synthetic-" + activity.path("id").asText()));
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Post> posts() { return List.copyOf(posts); }

    public Map<String,JsonNode> activities() {return Map.copyOf(activities);}

    public List<Map<String,Object>> mutations() {return List.copyOf(mutations);}

    public void beforeReceipt(Runnable callback) { beforeReceipt = callback; }

    public void reset() {
        posts.clear(); mutations.clear(); activities.clear();
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
                MutationResult mutate(URI endpoint,String token,MutationCommand command) {
                    return super.mutate(fixture.base.resolve(endpoint.getRawPath()),token,command);
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
