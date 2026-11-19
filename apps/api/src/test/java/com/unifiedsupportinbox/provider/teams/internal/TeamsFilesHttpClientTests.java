package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsFilesHttpClientTests {
    final ObjectMapper json=new ObjectMapper();
    final UUID integration=UUID.randomUUID();
    final TeamsAccessTokens tokens=mock(TeamsAccessTokens.class);
    TeamsFilesHttpClient client(HttpServer server,Set<String> origins) {
        when(tokens.token(eq(integration),anyString())).thenReturn("synthetic-provider-token");
        return new TeamsFilesHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),json,tokens,
                URI.create(origin(server)+"/v1.0/"),origins);
    }
    static String origin(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
    tools.jackson.databind.node.ObjectNode activity(String url) {
        return (tools.jackson.databind.node.ObjectNode)json.readTree("""
          {"type":"message","id":"reply","text":"caption","timestamp":"2026-11-19T12:00:00Z",
           "channelId":"msteams","from":{"id":"human"},"recipient":{"id":"28:bot"},"serviceUrl":"https://smba.trafficmanager.net/emea/",
           "conversation":{"id":"conversation;messageid=root","conversationType":"channel"},
           "channelData":{"channel":{"id":"channel"},"team":{"aadGroupId":"11111111-1111-4111-8111-111111111111"}},
           "attachments":[{"contentType":"image/png","name":"image.png","contentUrl":"%s"}]}
          """.formatted(url));
    }
    @Test void referencedChannelFilesUseRscMessageReadAndNeverForwardGraphBearerToStorage() throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var storage=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] bytes="test-pdf-bytes".getBytes(StandardCharsets.UTF_8);
        var receivedBearer=new AtomicReference<String>();
        storage.createContext("/content",e->{receivedBearer.set(e.getRequestHeaders().getFirst("Authorization"));e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        graph.createContext("/",e->{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-provider-token");
            String path=e.getRequestURI().getPath();String body;
            if(path.endsWith("/content")) {e.getResponseHeaders().set("Location",origin(storage)+"/content");e.sendResponseHeaders(302,-1);e.close();return;}
            if(path.contains("/root:/"))body="{\"id\":\"item\",\"name\":\"evidence.pdf\",\"size\":"+bytes.length+",\"file\":{\"mimeType\":\"application/pdf\"},\"parentReference\":{\"driveId\":\"drive\"}}";
            else if(path.endsWith("/drives/drive"))body="{\"id\":\"drive\",\"webUrl\":\""+origin(storage)+"/library\"}";
            else {assertThat(path).endsWith("/messages/root/replies/reply");body="{\"attachments\":[{\"id\":\"ref\",\"name\":\"evidence.pdf\",\"contentType\":\"reference\",\"contentUrl\":\""+origin(storage)+"/library/evidence.pdf\"}]}";}
            byte[] encoded=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,encoded.length);e.getResponseBody().write(encoded);e.close();
        });
        graph.start();storage.start();
        try {
            var c=client(graph,Set.of(origin(graph),origin(storage)));var a=activity(origin(graph)+"/unused");
            a.withArrayProperty("attachments").removeAll().addObject().put("contentType","text/html").put("content","caption");
            var sources=c.discover(integration,a);assertThat(sources).hasSize(1);
            Path temp;
            try(var downloaded=c.download(integration,a,sources.getFirst(),new TeamsFilesHttpClient.FileLocation("drive",null,Set.of()))) {
                temp=downloaded.path();assertThat(Files.readAllBytes(temp)).isEqualTo(bytes);
                assertThat(downloaded.filename()).isEqualTo("evidence.pdf");assertThat(downloaded.contentType()).isEqualTo("application/pdf");
                assertThat(downloaded.sha256()).hasSize(64);
            }
            assertThat(Files.exists(temp)).isFalse();assertThat(receivedBearer.get()).isNull();
            verify(tokens,atLeastOnce()).token(integration,"https://graph.microsoft.com/.default");
        }finally{graph.stop(0);storage.stop(0);}
    }
    @Test void untrustedInlineSourceNeverReceivesABearerOrNetworkRequest() throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);graph.start();
        try {
            var c=client(graph,Set.of(origin(graph)));
            assertThatThrownBy(()->c.download(integration,activity("https://attacker.example/private"),
                    new TeamsFilesHttpClient.Source("inline:0","image.png","image/png","INLINE",0),new TeamsFilesHttpClient.FileLocation(null,null,Set.of())))
                    .isInstanceOfSatisfying(ProviderAttachmentException.class,e->assertThat(e.errorCode()).isEqualTo("TEAMS_UNTRUSTED_FILE_SOURCE"));
            verifyNoInteractions(tokens);
        }finally{graph.stop(0);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"image/png,.png","image/jpeg,.jpg","image/avif,.avif"})
    void htmlOnlyPastedImageUsesCanonicalHostedContentsEndpoint(String mime,String extension) throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] bytes="hosted-image".getBytes(StandardCharsets.UTF_8);
        graph.createContext("/",e->{
            String path=e.getRequestURI().getPath();
            if(path.endsWith("/hostedContents/hosted-id/$value")){e.getResponseHeaders().set("Content-Type",mime);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();return;}
            String body=path.endsWith("/hostedContents")?"{\"value\":[{\"id\":\"hosted-id\",\"contentType\":null}]}":"{\"body\":{\"content\":\"<img src='untrusted-html-source'>\"},\"attachments\":[]}";
            byte[] data=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();
        });graph.start();
        try {
            var c=client(graph,Set.of(origin(graph)));var a=activity(origin(graph)+"/unused");
            a.withArrayProperty("attachments").removeAll().addObject().put("contentType","text/html");
            a.withArrayProperty("attachments").addObject().put("contentType","image/*").put("contentUrl","https://smba.trafficmanager.net/emea/v3/attachments/image/views/original");
            var sources=c.discover(integration,a);assertThat(sources).hasSize(1);
            assertThat(sources.getFirst().kind()).isEqualTo("HOSTED");
            try(var download=c.download(integration,a,sources.getFirst(),new TeamsFilesHttpClient.FileLocation(null,null,Set.of()))) {
                assertThat(Files.readAllBytes(download.path())).isEqualTo(bytes);assertThat(download.contentType()).isEqualTo(mime);assertThat(download.filename()).endsWith(extension);
            }
        }finally{graph.stop(0);}
    }
    @Test void chunkedFileOverTwentyFiveMiBIsStoppedAndNotReturned() throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        graph.createContext("/image",e->{e.sendResponseHeaders(200,0);try(var out=e.getResponseBody()){byte[] chunk=new byte[16384];for(int i=0;i<=1600;i++)out.write(chunk);}catch(java.io.IOException expectedClientAbort){}finally{e.close();}});
        graph.start();
        try {
            var c=client(graph,Set.of(origin(graph)));
            assertThatThrownBy(()->c.download(integration,activity(origin(graph)+"/image"),
                    new TeamsFilesHttpClient.Source("inline:0","image.png","image/png","INLINE",0),new TeamsFilesHttpClient.FileLocation(null,null,Set.of())))
                    .isInstanceOfSatisfying(ProviderAttachmentException.class,e->assertThat(e.errorCode()).isEqualTo("POLICY_FILE_SIZE"));
        }finally{graph.stop(0);}
    }
    @Test void filePermissionFailureAndRateLimitAreDifferentRepairPaths() throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var status=new java.util.concurrent.atomic.AtomicInteger(403);
        graph.createContext("/",e->{e.getResponseHeaders().set("Retry-After","7");e.sendResponseHeaders(status.get(),-1);e.close();});graph.start();
        try {
            var c=client(graph,Set.of(origin(graph)));var a=activity(origin(graph)+"/image");
            a.withArrayProperty("attachments").removeAll().addObject().put("contentType","text/html");
            assertThatThrownBy(()->c.discover(integration,a)).isInstanceOfSatisfying(ProviderAttachmentException.class,e->{assertThat(e.errorCode()).isEqualTo("TEAMS_FILE_PERMISSION_REQUIRED");assertThat(e.retryable()).isFalse();});
            status.set(429);
            assertThatThrownBy(()->c.discover(integration,a)).isInstanceOfSatisfying(ProviderAttachmentException.class,e->{assertThat(e.errorCode()).isEqualTo("TEAMS_FILE_RATE_LIMITED");assertThat(e.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(7));assertThat(e.retryable()).isTrue();});
        }finally{graph.stop(0);}
    }
    @Test void deterministicUploadStreamsOriginalBytesAndReturnsAStoredFileLink() throws Exception {
        var graph=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] bytes="synthetic-document".getBytes(StandardCharsets.UTF_8);
        var path=new AtomicReference<String>();
        graph.createContext("/",e->{path.set(e.getRequestURI().getPath());assertThat(e.getRequestMethod()).isEqualTo("PUT");
            assertThat(e.getRequestHeaders().getFirst("Content-Length")).isEqualTo(String.valueOf(bytes.length));assertThat(e.getRequestBody().readAllBytes()).isEqualTo(bytes);
            byte[] reply=("{\"id\":\"item\",\"webUrl\":\""+origin(graph)+"/document\"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(201,reply.length);e.getResponseBody().write(reply);e.close();});graph.start();
        try {
            var c=client(graph,Set.of(origin(graph)));
            var result=c.upload(integration,new TeamsFilesHttpClient.FileLocation("drive","folder",Set.of("tenant.sharepoint.com")),"stable-key.pdf","original.pdf","application/pdf",bytes.length,()->new java.io.ByteArrayInputStream(bytes));
            assertThat(result.itemId()).isEqualTo("item");assertThat(result.name()).isEqualTo("original.pdf");
            assertThat(path.get()).isEqualTo("/v1.0/drives/drive/items/folder:/stable-key.pdf:/content");
        }finally{graph.stop(0);}
    }
}
