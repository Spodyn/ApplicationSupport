package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class DirectMessageIntegrationTests {
    @Test
    void upgradesV41AndSerializesConcurrentStartsAndSends() throws Exception {
        try (var postgres = TestInfrastructure.postgres()) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).target("41").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID alice = user(jdbc, "Alice");
            UUID bob = user(jdbc, "Bob");
            UUID carol = user(jdbc, "Carol");
            Flyway current = Flyway.configure().dataSource(source).load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            current.validate();
            assertThat(current.info().pending()).isEmpty();
            try (var context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                    "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.datasource.driver-class-name=" + postgres.getDriverClassName(),
                    "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                    "--usi.bootstrap-admin.enabled=false");
                    var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var service = context.getBean(DirectMessageService.class);
                var fromAlice = executor.submit(() -> service.start(alice, bob));
                var fromBob = executor.submit(() -> service.start(bob, alice));
                UUID conversation = fromAlice.get(10, TimeUnit.SECONDS).id();
                assertThat(fromBob.get(10, TimeUnit.SECONDS).id()).isEqualTo(conversation);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM direct_conversations", Integer.class)).isEqualTo(1);

                String firstKey = UUID.randomUUID().toString();
                var first = executor.submit(() -> service.send(alice, conversation, firstKey, "Alice text"));
                var second = executor.submit(() -> service.send(bob, conversation, UUID.randomUUID().toString(), "Bob text"));
                assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
                assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
                assertThat(service.send(alice, conversation, firstKey, "Alice text").body())
                        .isEqualTo(first.get().body());
                assertThatThrownBy(() -> service.send(alice, conversation, firstKey, "Changed text"))
                        .isInstanceOf(ApiProblemException.class).hasMessageContaining("different request");
                assertThatThrownBy(() -> service.messages(carol, conversation, null, 10))
                        .isInstanceOf(ApiProblemException.class).hasMessage("Direct conversation not found.");
                var messages = service.messages(alice, conversation, null, 10).items();
                assertThat(messages).hasSize(2);
                service.read(alice, conversation, messages.getFirst().id());
                service.read(alice, conversation, messages.getLast().id());
                assertThat(service.conversation(alice, conversation).unreadCount()).isZero();
                assertThat(service.conversation(bob, conversation).unreadCount()).isEqualTo(1);
            }
        }
    }

    private UUID user(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role) VALUES (?, ?, 'USER') RETURNING id
                """, UUID.class, name.toLowerCase(java.util.Locale.ROOT) + "@example.test", name);
    }
}
