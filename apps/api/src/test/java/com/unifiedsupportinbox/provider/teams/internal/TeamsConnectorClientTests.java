package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsConnectorClientTests {
    @Test void syntheticTokenAndConnectorHttpPreserveThreadAndClassifyFailures() throws Exception {
        var json=new ObjectMapper(); var status=new AtomicInteger(200); var captured=new AtomicReference<String>();var responseBody=new AtomicReference<>("{\"id\":\"provider-reply\"}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/token",e->{
            captured.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"access_token\":\"synthetic-token\"}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/messages",e->{
            captured.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=responseBody.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Retry-After","17"); e.sendResponseHeaders(status.get(),body.length);
            e.getResponseBody().write(body);e.close();
        });server.start();
        try {
            var acceptance=org.mockito.Mockito.mock(TeamsOutboundAcceptance.class);
            var client=new TeamsConnectorClient(HttpClient.newHttpClient(),json,acceptance);
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            assertThat(client.token(base.resolve("/token"),"test-client","synthetic-secret".getBytes(StandardCharsets.UTF_8))).isEqualTo("synthetic-token");
            assertThat(captured.get()).contains("grant_type=client_credentials","scope=https%3A%2F%2Fapi.botframework.com%2F.default");
            var accepted=new AtomicReference<String>();
            var command=new MessageDeliveryProvider.DeliveryCommand(UUID.randomUUID(),"idempotency",UUID.randomUUID(),IntegrationProvider.TEAMS,
                    UUID.randomUUID(),"channel","root-1","reply from inbox",MessageBodyFormat.PLAIN_TEXT,"correlation",null,accepted::set);
            var route=new TeamsRouteStore.Route("19:channel;messageid=root-1","https://smba.trafficmanager.net/emea/","28:bot","standardChannel");
            assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
            assertThat(accepted.get()).isEqualTo("provider-reply");
            assertThat(json.readTree(captured.get()).path("replyToId").asText()).isEqualTo("root-1");
            assertThat(json.readTree(captured.get()).path("conversation").path("id").asText()).isEqualTo(route.conversationId());
            status.set(429); var limited=client.send(base.resolve("/messages"),"synthetic-token",route,command);
            assertThat(limited.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(17));
            assertThat(limited.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
            status.set(403); assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).errorCode()).isEqualTo("TEAMS_PERMISSION_DENIED");
            status.set(503); assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
            status.set(202);responseBody.set("");accepted.set(null);
            var asynchronous=client.send(base.resolve("/messages"),"synthetic-token",route,command);
            assertThat(asynchronous.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
            assertThat(asynchronous.providerMessageRef()).isNull();assertThat(accepted.get()).isNull();
            org.mockito.Mockito.verify(acceptance).record(command);
            responseBody.set("{}");assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
            org.mockito.Mockito.doThrow(new IllegalStateException("synthetic checkpoint failure")).when(acceptance).record(command);
            assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).errorCode()).isEqualTo("TEAMS_DELIVERY_UNCONFIRMED");
            responseBody.set("");
            status.set(200);assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).errorCode()).isEqualTo("TEAMS_DELIVERY_UNCONFIRMED");
            responseBody.set("invalid-json");
            assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,command).errorCode()).isEqualTo("TEAMS_DELIVERY_UNCONFIRMED");
            responseBody.set("{\"id\":\"accepted-but-checkpoint-failed\"}");
            var failedCheckpoint=new MessageDeliveryProvider.DeliveryCommand(command.messageId(),command.idempotencyKey(),command.caseId(),command.provider(),
                    command.integrationId(),command.externalConversationId(),command.externalThreadKey(),command.body(),command.bodyFormat(),command.correlationId(),null,
                    ref->{throw new IllegalStateException("synthetic checkpoint failure");});
            assertThat(client.send(base.resolve("/messages"),"synthetic-token",route,failedCheckpoint).errorCode()).isEqualTo("TEAMS_DELIVERY_UNCONFIRMED");
        } finally {server.stop(0);}
    }
}
