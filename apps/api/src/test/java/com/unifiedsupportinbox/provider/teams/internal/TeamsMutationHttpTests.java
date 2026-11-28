package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsMutationHttpTests {
    @Test void connectorPutAndDeletePreserveIdentityAndAttachmentsAndNeverConfirmAnAsyncAcceptance() throws Exception {
        var json=new ObjectMapper();var status=new AtomicInteger(200);var method=new AtomicReference<String>();var body=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/activity",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            method.set(exchange.getRequestMethod());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Retry-After","17");
            exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var client=new TeamsConnectorClient(HttpClient.newHttpClient(),json,org.mockito.Mockito.mock(TeamsOutboundAcceptance.class));
            var target=new MutationTarget("actual-activity","19:conversation","28:bot","https://smba.trafficmanager.net/emea/","standardChannel",
                    "[{\"contentType\":\"reference\",\"contentUrl\":\"https://files.example.test/retained\",\"name\":\"report.txt\"}]","test-only");
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/activity");
            var edit=command("EDIT",target);
            assertThat(client.mutate(endpoint,"synthetic-token",edit).outcome()).isEqualTo(MutationOutcome.CONFIRMED);
            assertThat(method.get()).isEqualTo("PUT");var payload=json.readTree(body.get());
            assertThat(payload.path("id").asText()).isEqualTo(target.providerMessageRef());
            assertThat(payload.path("from").path("id").asText()).isEqualTo(target.senderId());
            assertThat(payload.path("conversation").path("id").asText()).isEqualTo(target.conversationId());
            assertThat(payload.path("attachments")).isEqualTo(json.readTree(target.attachmentPayload()));
            assertThat(payload.path("text").asText()).isEqualTo("edited text");
            assertThat(client.mutate(endpoint,"synthetic-token",command("DELETE",target)).outcome()).isEqualTo(MutationOutcome.CONFIRMED);
            assertThat(method.get()).isEqualTo("DELETE");assertThat(body.get()).isEmpty();
            status.set(202);assertThat(client.mutate(endpoint,"synthetic-token",edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            status.set(503);assertThat(client.mutate(endpoint,"synthetic-token",edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            status.set(429);assertThat(client.mutate(endpoint,"synthetic-token",edit).retryAfter()).isEqualTo(Duration.ofSeconds(17));
            status.set(404);assertThat(client.mutate(endpoint,"synthetic-token",command("DELETE",target)).outcome()).isEqualTo(MutationOutcome.FAILED);
            status.set(403);assertThat(client.mutate(endpoint,"synthetic-token",edit).errorCode()).isEqualTo("TEAMS_PERMISSION_DENIED");
            var unsafe=new MutationTarget("id","conversation","bot","http://127.0.0.1/","standardChannel","[]","test-only");
            assertThat(client.mutate("synthetic-token",command("EDIT",unsafe)).errorCode()).isEqualTo("TEAMS_UNTRUSTED_SERVICE_URL");
        } finally {server.stop(0);}
    }
    private static MutationCommand command(String action,MutationTarget target) {
        return new MutationCommand(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),IntegrationProvider.TEAMS,"channel",action,"edited text",MessageBodyFormat.PLAIN_TEXT,target);
    }
}
