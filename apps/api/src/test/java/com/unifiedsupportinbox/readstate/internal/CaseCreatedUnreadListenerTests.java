package com.unifiedsupportinbox.readstate.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

class CaseCreatedUnreadListenerTests {

    @Test
    void malformedCaseCreatedIsRejectedWithoutRequeue() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:unread-listener;MODE=PostgreSQL", "sa", "");
        var projection = new CaseCreatedUnreadProjection(new JdbcTemplate(dataSource));
        var listener = new CaseCreatedUnreadRabbitListener(projection, new ObjectMapper());

        assertThatThrownBy(() -> listener.onCaseCreated(new Message("{}".getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onCaseCreated(new Message(
                        ("{\"caseId\":\"not-a-uuid\"}").getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void syntacticallyValidButUnknownCaseIsRejectedWithoutRequeue() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:unread-listener-unknown;MODE=PostgreSQL", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE cases (id uuid PRIMARY KEY)");
        var projection = new CaseCreatedUnreadProjection(jdbc);
        var listener = new CaseCreatedUnreadRabbitListener(projection, new ObjectMapper());

        assertThatThrownBy(() -> listener.onCaseCreated(new Message(
                        ("{\"caseId\":\"" + UUID.randomUUID() + "\"}").getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
