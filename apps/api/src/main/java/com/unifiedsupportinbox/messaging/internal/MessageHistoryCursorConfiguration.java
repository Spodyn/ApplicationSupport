package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MessageHistoryCursorConfiguration {

    @Bean
    CursorCodec messageHistoryCursorCodec(@Value("${usi.pagination.cursor-signing-key}") String signingKey) {
        return new CursorCodec(sha256(signingKey), Clock.systemUTC());
    }

    private static byte[] sha256(String signingKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(signingKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
