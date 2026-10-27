package com.ifp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingUnitTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void backoffDoublesAndIsCapped() {
        Duration max = Duration.ofMinutes(5);
        assertThat(OutboxRelay.backoff(1, max)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.backoff(2, max)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxRelay.backoff(5, max)).isEqualTo(Duration.ofSeconds(32));
        assertThat(OutboxRelay.backoff(9, max)).isEqualTo(max);
        assertThat(OutboxRelay.backoff(100, max)).isEqualTo(max);
    }

    @Test
    void permanentFailuresAreNotRetried() {
        SimpleRetryPolicy policy = MessagingAutoConfiguration.retryPolicy(4);

        RetryContext poison = policy.open(null);
        policy.registerThrowable(poison, new ListenerExecutionFailedException("x", new AmqpRejectAndDontRequeueException("bad")));
        assertThat(policy.canRetry(poison)).isFalse();

        RetryContext transientFailure = policy.open(null);
        policy.registerThrowable(transientFailure, new ListenerExecutionFailedException("x", new IllegalStateException("db down")));
        assertThat(policy.canRetry(transientFailure)).isTrue();
    }

    @Test
    void consumerQueueDeadLettersToItsOwnDlq() {
        var declarables = EventTopology.consumerQueue("svc.events", "invoice.*", "financing.settled");

        Queue main = declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals("svc.events")).findFirst().orElseThrow();
        assertThat(main.getArguments()).containsEntry("x-dead-letter-exchange", EventTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "svc.events");
        assertThat(declarables.getDeclarablesByType(Binding.class))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("svc.events", "invoice.*", "financing.settled");
    }

    @Test
    void malformedAndUnsupportedMessagesAreRejectedWithoutRequeue() {
        InboundEventProcessor processor = new InboundEventProcessor(mapper, null, null, null);

        assertThatThrownBy(() -> processor.parse(message("{not json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> processor.parse(message("""
                {"eventId":"6f1c2b9e-0000-4000-8000-000000000001","eventType":"X","schemaVersion":2,
                 "occurredAt":"2026-01-01T00:00:00Z","producer":"p","aggregateType":"A","aggregateId":"1","payload":{}}""")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("schema version 2");
        assertThat(processor.parse(message("""
                {"eventId":"6f1c2b9e-0000-4000-8000-000000000001","eventType":"X","schemaVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z","producer":"p","aggregateType":"A","aggregateId":"1","payload":{"a":1}}""")).payload().get("a").asInt())
                .isEqualTo(1);
    }

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
