package com.unifiedsupportinbox.provider.slack.internal;

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

class SlackMutationHttpTests {
    @Test void correctBotConversationAndTimestampAreUsedAndOnlyPositiveReceiptsConfirm() throws Exception {
        var json=new ObjectMapper();var status=new AtomicInteger(200);
        var response=new AtomicReference<>("{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1790000000.123456\"}");
        var captured=new AtomicReference<String>();var path=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-bot-token");
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            path.set(exchange.getRequestURI().getPath());
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After","17");exchange.sendResponseHeaders(status.get(),bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var client=new HttpSlackWebApiClient(HttpClient.newHttpClient(),json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat.postMessage"));
            byte[] token=("synthetic-"+"bot-token").getBytes(StandardCharsets.UTF_8);
            var target=new MutationTarget("1790000000.123456","C123","B123",null,null,"[]","test-only");
            var edit=command("EDIT",target);
            assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.CONFIRMED);
            assertThat(path.get()).isEqualTo("/api/chat.update");
            var body=json.readTree(captured.get());
            assertThat(body.path("channel").asText()).isEqualTo("C123");
            assertThat(body.path("ts").asText()).isEqualTo(target.providerMessageRef());
            assertThat(body.path("as_user").asBoolean()).isTrue();
            assertThat(body.path("text").asText()).isEqualTo("edited text");
            assertThat(body.path("parse").asText()).isEqualTo("none");
            assertThat(body.has("mrkdwn")).isFalse();
            var plain=new MutationCommand(edit.operationId(),edit.messageId(),edit.integrationId(),edit.provider(),edit.externalChannelId(),"EDIT","literal *text*",MessageBodyFormat.PLAIN_TEXT,target);
            assertThat(client.mutate(token,plain).outcome()).isEqualTo(MutationOutcome.CONFIRMED);
            assertThat(json.readTree(captured.get()).path("parse").asText()).isEqualTo("full");
            assertThat(json.readTree(captured.get()).path("link_names").asBoolean(true)).isFalse();
            assertThat(body.has("attachments")).isFalse();assertThat(body.has("files")).isFalse();
            assertThat(client.mutate(token,command("DELETE",target)).outcome()).isEqualTo(MutationOutcome.CONFIRMED);
            assertThat(path.get()).isEqualTo("/api/chat.delete");assertThat(json.readTree(captured.get()).has("text")).isFalse();
            status.set(429);assertThat(client.mutate(token,edit).retryAfter()).isEqualTo(Duration.ofSeconds(17));
            assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.RETRY);
            status.set(503);assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            status.set(403);assertThat(client.mutate(token,edit).errorCode()).isEqualTo("SLACK_PERMISSION_DENIED");
            status.set(200);response.set("{\"ok\":false,\"error\":\"message_not_found\"}");
            assertThat(client.mutate(token,command("DELETE",target)).outcome()).isEqualTo(MutationOutcome.FAILED);
            response.set("{\"ok\":true,\"channel\":\"WRONG\",\"ts\":\"1790000000.123456\"}");
            assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            response.set("{}");assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            response.set("");assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            status.set(202);response.set("{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1790000000.123456\"}");
            assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
            status.set(200);response.set("not json");assertThat(client.mutate(token,edit).outcome()).isEqualTo(MutationOutcome.UNCERTAIN);
        } finally {server.stop(0);}
    }
    private static MutationCommand command(String action,MutationTarget target) {
        return new MutationCommand(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),IntegrationProvider.SLACK,"C123",action,"edited text",MessageBodyFormat.MARKDOWN,target);
    }
}
