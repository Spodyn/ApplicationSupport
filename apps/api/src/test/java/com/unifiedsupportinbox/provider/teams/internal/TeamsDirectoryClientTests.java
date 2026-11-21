package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.integration.ProviderChannelDiscovery.Failure;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class TeamsDirectoryClientTests {
    final TeamsAccessTokens tokens=mock(TeamsAccessTokens.class);
    final UUID integration=UUID.randomUUID(),team=UUID.randomUUID();
    HttpServer server; URI origin; TeamsDirectoryClient directory;
    AtomicInteger status=new AtomicInteger(200),requests=new AtomicInteger();
    AtomicReference<String> continuation=new AtomicReference<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1.0/teams/"+team+"/channels");
            String next=exchange.getRequestURI().getQuery()==null?continuation.get():null;
            String body="{\"value\":[{\"id\":\"channel\",\"displayName\":\"Support\",\"membershipType\":\"standard\"}]"+(next==null?"":",\"@odata.nextLink\":\""+next+"\"")+"}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After","120");
            exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1.0/");
        when(tokens.configurationToken(eq(integration),anyString())).thenReturn("synthetic-token");
        directory=new TeamsDirectoryClient(tokens,new ObjectMapper(),HttpClient.newHttpClient(),origin);
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void readsAllPagesOfOnlyTheSelectedTeam() {
        continuation.set(origin+"teams/"+team+"/channels?$skiptoken=second");
        assertThat(directory.channels(integration,team)).hasSize(2);assertThat(requests).hasValue(2);
    }
    @Test void foreignContinuationNeverReceivesTheBearerAndCyclesAreRejected() {
        continuation.set("https://attacker.example/channels");
        assertThatThrownBy(()->directory.channels(integration,team)).isInstanceOfSatisfying(Failure.class,e->assertThat(e.code).isEqualTo("TEAMS_DISCOVERY_CONTINUATION_REJECTED"));
        assertThat(requests).hasValue(1);
        continuation.set(origin+"teams/"+team+"/channels");
        assertThatThrownBy(()->directory.channels(integration,team)).isInstanceOf(Failure.class);assertThat(requests).hasValue(2);
    }
    @Test void permissionFailureAndRateLimitExposeSafeCodesAndRetryAfter() {
        status.set(403);assertThatThrownBy(()->directory.channels(integration,team)).isInstanceOfSatisfying(Failure.class,e->{assertThat(e.code).isEqualTo("TEAMS_DISCOVERY_HTTP_403");assertThat(e.retryAfter).isNull();});
        for(int code:new int[]{429,503}){status.set(code);assertThatThrownBy(()->directory.channels(integration,team)).isInstanceOfSatisfying(Failure.class,e->assertThat(e.retryAfter).isEqualTo(Duration.ofSeconds(120)));}
    }
}
