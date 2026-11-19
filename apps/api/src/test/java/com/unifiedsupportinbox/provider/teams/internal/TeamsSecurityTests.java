package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsSecurityTests {
    static final String CLIENT="11111111-1111-4111-8111-111111111111", TENANT="22222222-2222-4222-8222-222222222222";
    static final Instant NOW=Instant.parse("2026-11-19T12:00:00Z");
    static final ObjectMapper JSON=new ObjectMapper();
    static final RSAKey KEY=key();
    static RSAKey key() { try { return new RSAKeyGenerator(2048).keyID("synthetic-key").generate(); } catch(Exception e) { throw new RuntimeException(e); } }
    TeamsJwtAuthenticator auth(Map<String,Object> endorsements) {
        return new TeamsJwtAuthenticator(CLIENT,TENANT,()->new TeamsJwtAuthenticator.SigningKeys(new JWKSet(KEY.toPublicJWK()),endorsements),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    String token(String issuer,String audience,Instant expires,Instant nbf,String service) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).audience(audience).expirationTime(Date.from(expires))
                .notBeforeTime(Date.from(nbf)).claim("serviceurl",service).build());
        jwt.sign(new RSASSASigner(KEY)); return "Bearer "+jwt.serialize();
    }
    tools.jackson.databind.node.ObjectNode activity() {
        return (tools.jackson.databind.node.ObjectNode)JSON.readTree("""
                {"channelId":"msteams","recipient":{"id":"28:11111111-1111-4111-8111-111111111111"},
                 "serviceUrl":"https://smba.trafficmanager.net/emea/",
                 "channelData":{"tenant":{"id":"22222222-2222-4222-8222-222222222222"}}}
                """);
    }
    @Test void signedConnectorActivityRequiresAudienceIssuerTimeServiceTenantAndRecipient() throws Exception {
        String service="https://smba.trafficmanager.net/emea/";
        String valid=token("https://api.botframework.com",CLIENT,NOW.plusSeconds(300),NOW.minusSeconds(60),service);
        assertThatCode(()->auth(Map.of(KEY.getKeyID(),List.of("msteams"))).authenticate(valid,activity())).doesNotThrowAnyException();
        for(String invalid:List.of("", "Bearer invalid",valid.substring(0,valid.length()-8)+"tampered",
                token("https://evil.example",CLIENT,NOW.plusSeconds(300),NOW.minusSeconds(60),service),
                token("https://api.botframework.com","wrong-audience",NOW.plusSeconds(300),NOW.minusSeconds(60),service),
                token("https://api.botframework.com",CLIENT,NOW.minusSeconds(120),NOW.minusSeconds(300),service),
                token("https://api.botframework.com",CLIENT,NOW.plusSeconds(300),NOW.plusSeconds(120),service))) {
            assertThatThrownBy(()->auth(Map.of()).authenticate(invalid,activity())).isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        }
        assertThatThrownBy(()->auth(Map.of(KEY.getKeyID(),List.of("webchat"))).authenticate(valid,activity())).isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        for(String field:List.of("tenant","service","recipient","channel")) {
            var a=activity();
            switch(field) {
                case "tenant" -> a.withObjectProperty("channelData").withObjectProperty("tenant").put("id","other-tenant");
                case "service" -> a.put("serviceUrl","https://attacker.example/");
                case "recipient" -> a.withObjectProperty("recipient").put("id","another-bot");
                case "channel" -> a.put("channelId","webchat");
            }
            assertThatThrownBy(()->auth(Map.of()).authenticate(valid,a)).isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        }
    }
    @Test void serviceUrlCannotRedirectCredentialsToUntrustedHostsOrPaths() {
        for(String url:List.of("http://smba.trafficmanager.net/emea/","https://smba.trafficmanager.net.evil.test/emea/",
                "https://smba.trafficmanager.net@evil.test/","https://smba.trafficmanager.net:443/emea/",
                "https://smba.trafficmanager.net/../evil","https://smba.trafficmanager.net/emea/?token=secret"))
            assertThat(TeamsJwtAuthenticator.safeServiceUrl(url)).isFalse();
    }
    @Test void authenticationDiagnosticsExposeOnlyAControlledStage() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TeamsJwtAuthenticator.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            assertThatThrownBy(()->auth(Map.of()).authenticate("Bearer test-only-sensitive-value",activity()))
                    .isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("Teams callback authentication rejected; stage=TOKEN_FORMAT")
                    .doesNotContain("Bearer","sensitive-value",TENANT,CLIENT);
        } finally {logger.detachAppender(appender);appender.stop();}
    }
}
