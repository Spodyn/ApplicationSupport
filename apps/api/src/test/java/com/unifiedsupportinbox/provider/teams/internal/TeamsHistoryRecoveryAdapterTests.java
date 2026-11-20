package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class TeamsHistoryRecoveryAdapterTests {
    ObjectMapper json=new ObjectMapper();
    TeamsAccessTokens tokens=mock(TeamsAccessTokens.class);
    HttpServer server; TeamsHistoryRecoveryAdapter adapter; URI origin;
    AtomicInteger code=new AtomicInteger(200); AtomicReference<String> body=new AtomicReference<>();
    List<String> paths=new java.util.concurrent.CopyOnWriteArrayList<>();
    Instant now=Instant.parse("2026-11-20T10:30:00Z");
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());byte[] data=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After","120");exchange.sendResponseHeaders(exchange.getRequestURI().getRawQuery().contains("skiptoken=expired")?410:code.get(),data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();
        origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1.0/");
        when(tokens.token(any(),anyString())).thenReturn("synthetic-graph-token");
        adapter=new TeamsHistoryRecoveryAdapter(tokens,mock(TeamsInboundDeliveryService.class),mock(JdbcTemplate.class),json,HttpClient.newHttpClient(),origin);
    }
    @AfterEach void stop(){server.stop(0);}
    Task task(String thread,Instant from,String cursor,String context) {return new Task(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"channel","TEAMS","automatic",from,now,thread,cursor,UUID.randomUUID(),json.readTree("{\"teamsContextType\":\""+context+"\",\"teamsTeamId\":\"11111111-1111-1111-1111-111111111111\"}"));}
    String message(String id,String sent){return "{\"id\":\""+id+"\",\"createdDateTime\":\""+sent+"\",\"from\":{\"user\":{\"id\":\"user\"}}}";}
    @Test void pagesRootsAndRepliesIncludingOldThreadsAndBothManualRanges() {
        body.set("{\"value\":["+message("old","2026-11-01T10:00:00Z")+","+message("new","2026-11-20T09:30:00Z")+"],\"@odata.nextLink\":\""+origin+"teams/11111111-1111-1111-1111-111111111111/channels/channel/messages?$skiptoken=next\"}");
        var roots=adapter.fetch(task("",now.minusSeconds(7200),null,"standardChannel"));
        assertThat(roots.threads()).containsExactly("old","new");assertThat(roots.messages()).hasSize(1);assertThat(roots.nextCursor()).contains("skiptoken");
        assertThat(adapter.fetch(task("",null,roots.nextCursor(),"standardChannel")).messages()).hasSize(2);
        body.set("{\"value\":["+message("reply","2026-11-20T09:45:00Z")+"]}");
        assertThat(adapter.fetch(task("old",now.minusSeconds(7200),null,"standardChannel")).messages()).hasSize(1);
        assertThat(paths.getLast()).endsWith("/messages/old/replies");
        adapter.fetch(task("",null,null,"groupChat"));assertThat(paths.getLast()).endsWith("/chats/channel/messages");
    }
    @Test void refusesForeignContinuationAndUnsupportedContextsBeforeExposingBearer() {
        assertThatThrownBy(()->adapter.fetch(task("",null,"https://attacker.example/v1.0/messages","standardChannel"))).isInstanceOf(Unavailable.class);
        assertThatThrownBy(()->adapter.fetch(task("",null,null,"privateChannel"))).isInstanceOf(Unavailable.class);
        verifyNoInteractions(tokens);assertThat(paths).isEmpty();
    }
    @Test void permissionsOutageAndRateLimitsReturnSafePerChannelReasons() {
        body.set("{}");code.set(403);
        assertThatThrownBy(()->adapter.fetch(task("",null,null,"standardChannel"))).isInstanceOfSatisfying(Unavailable.class,e->{assertThat(e.code).isEqualTo("TEAMS_HISTORY_HTTP_403");assertThat(e.retryAfter).isNull();});
        for(int status:new int[]{429,503}) {code.set(status);
            assertThatThrownBy(()->adapter.fetch(task("",null,null,"standardChannel"))).isInstanceOfSatisfying(Unavailable.class,e->assertThat(e.retryAfter).isEqualTo(Duration.ofSeconds(120)));}
    }
    @Test void expiredContinuationRestartsTheCapturedCollectionAndRange() {
        body.set("{\"value\":["+message("new","2026-11-20T09:30:00Z")+"]}");
        String expired=origin+"teams/11111111-1111-1111-1111-111111111111/channels/channel/messages?$skiptoken=expired";
        assertThat(adapter.fetch(task("",now.minusSeconds(7200),expired,"standardChannel")).messages()).hasSize(1);
        assertThat(paths).hasSize(2);
    }
}
