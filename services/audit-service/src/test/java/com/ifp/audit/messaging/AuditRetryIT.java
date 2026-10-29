package com.ifp.audit.messaging;

import com.ifp.audit.support.AbstractIntegrationTest;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Delayed retry tiers on audit.events. */
class AuditRetryIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void consumerQueueHasTheThreeDelayedRetryTiers() {
        for (Duration delay : EventTopology.DEFAULT_RETRY_DELAYS) {
            assertThat(amqpAdmin.getQueueProperties(EventTopology.retryQueueName(AuditEventsListener.QUEUE, delay)))
                    .as("tier %s", delay).isNotNull();
        }
    }

    /** A broker holding the queue from the old plain declaration must accept the new one unchanged. */
    @Test
    void existingQueueAndDeadLetterQueueDeclarationsAreUnchanged() {
        Declarables before = EventTopology.consumerQueue(AuditEventsListener.QUEUE, "#");
        Map<String, Queue> after = new HashMap<>();
        AuditEventsListener.auditEventsQueue().getDeclarablesByType(Queue.class).forEach(q -> after.put(q.getName(), q));

        for (Queue old : before.getDeclarablesByType(Queue.class)) {
            assertThat(after).containsKey(old.getName());
            assertThat(after.get(old.getName()).getArguments()).as(old.getName()).isEqualTo(old.getArguments());
            assertThat(after.get(old.getName()).isDurable()).isEqualTo(old.isDurable());
            assertThat(amqpAdmin.declareQueue(old)).as("broker accepts the old declaration of %s", old.getName())
                    .isEqualTo(old.getName());
        }
    }

    /** A message parked in a tier returns only after the tier's delay; a duplicate delivered directly is recorded once. */
    @Test
    void messageWaitingInARetryTierReturnsAfterItsDelayAndIsRecordedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String aggregate = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(new EventEnvelope(eventId, "FinancingSettled", EventEnvelope.SCHEMA_VERSION,
                Instant.now(), "test", "AGREEMENT", aggregate, null, null, objectMapper.createObjectNode()));
        String tier = EventTopology.retryQueueName(AuditEventsListener.QUEUE, EventTopology.DEFAULT_RETRY_DELAYS.getFirst());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        properties.setHeader(EventTopology.RETRY_COUNT_HEADER, 1);
        Instant parked = Instant.now();
        rabbit.send(EventTopology.RETRY_EXCHANGE, tier, new Message(body.getBytes(StandardCharsets.UTF_8), properties));

        Thread.sleep(3000);
        assertThat(count(aggregate)).as("still in %s", tier).isZero();
        await(() -> count(aggregate) == 1);
        assertThat(Duration.between(parked, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(9));

        MessageProperties direct = new MessageProperties();
        direct.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, "financing.settled", new Message(body.getBytes(StandardCharsets.UTF_8), direct));
        Thread.sleep(1500);
        assertThat(count(aggregate)).isEqualTo(1);
    }

    private int count(String aggregateId) {
        Integer value = jdbc.queryForObject("select count(*) from audit_events where aggregate_id = ?", Integer.class, aggregateId);
        return value == null ? 0 : value;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 20s");
            }
            Thread.sleep(100);
        }
    }
}
