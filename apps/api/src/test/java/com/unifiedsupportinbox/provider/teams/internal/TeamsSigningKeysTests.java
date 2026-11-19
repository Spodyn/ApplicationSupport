package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

class TeamsSigningKeysTests {
    @Test void oversizedKeyDocumentStillFailsClosed() throws Exception {
        byte[] body=TeamsSecurityTests.JSON.writeValueAsBytes(Map.of(
                "keys",List.of(TeamsSecurityTests.KEY.toPublicJWK().toJSONObject()),
                "testPadding","x".repeat(2 * 1024 * 1024)));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/keys",e->{e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        server.start();
        try {
            var source=new TeamsJwtAuthenticator.MicrosoftKeys(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/keys"));
            assertThatThrownBy(source::get).isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        } finally {server.stop(0);}
    }
    @Test void largeLegitimatePublicKeySetIsAcceptedAndCached() throws Exception {
        var keys=new ArrayList<Map<String,Object>>();
        for(int i=0;i<900;i++) {
            var key=new LinkedHashMap<>(TeamsSecurityTests.KEY.toPublicJWK().toJSONObject());
            key.put("kid","test-key-"+i); key.put("endorsements",List.of("msteams")); keys.add(key);
        }
        byte[] body=TeamsSecurityTests.JSON.writeValueAsBytes(Map.of("keys",keys));
        assertThat(body.length).isGreaterThan(262144);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/keys",e->{requests.incrementAndGet();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        server.start();
        try {
            var source=new TeamsJwtAuthenticator.MicrosoftKeys(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/keys"));
            assertThat(source.get().set().getKeys()).hasSize(900);
            assertThat(source.get().endorsements().get("test-key-1")).isEqualTo(List.of("msteams"));
            assertThat(requests.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
}
