package com.unifiedsupportinbox.messaging.internal;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
class MessageMutationRabbitConfiguration {
    @Bean Queue messageMutationQueue(){return QueueBuilder.durable("usi.messages.mutations")
            .deadLetterExchange(MessageDeliveryRabbitConfiguration.DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey(MessageDeliveryRabbitConfiguration.DEAD_LETTER_ROUTING_KEY).build();}
    @Bean Binding messageMutationBinding(@Qualifier("messageMutationQueue") Queue queue,TopicExchange usiOutboxExchange){
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with("message.mutation.requested");
    }
}

@Component
@ConditionalOnProperty(prefix="usi.messages.delivery-worker",name="enabled",havingValue="true")
class MessageMutationListener {
    private final MessageMutationWorker worker;
    private final MessageMutationService mutations;
    private final ObjectMapper json;
    MessageMutationListener(MessageMutationWorker worker,MessageMutationService mutations,ObjectMapper json){this.worker=worker;this.mutations=mutations;this.json=json;}
    @RabbitListener(queues="usi.messages.mutations")
    void receive(Message message) {
        UUID id;
        try{id=UUID.fromString(json.readTree(message.getBody()).path("operationId").asText());}
        catch(RuntimeException invalid){throw new AmqpRejectAndDontRequeueException("Invalid mutation wakeup",invalid);}
        worker.process(id);
    }
    @Scheduled(fixedDelayString="${usi.messages.delivery-worker.poll-interval:1s}")
    void redispatch(){mutations.redispatch();}
}
