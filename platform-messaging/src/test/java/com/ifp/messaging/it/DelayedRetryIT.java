package com.ifp.messaging.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.OutboxRelay;
import com.ifp.messaging.OutboxWriter;
import com.ifp.messaging.Uuids;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Delayed retry tiers (TTL queues dead-lettering back to the consumer queue) against a real broker. */
@SpringBootTest(classes = MessagingTestApp.class)
class DelayedRetryIT {

    private static final String DLQ = MessagingTestApp.TIERED_QUEUE + EventTopology.DLQ_SUFFIX;

    @Autowired
    private OutboxWriter outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin admin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MessagingTestApp.TieredConsumer consumer;

    @Test
    void tierQueuesAreDeclaredWithTheirDelays() {
        for (Duration delay : MessagingTestApp.TIERS) {
            Properties tier = admin.getQueueProperties(EventTopology.retryQueueName(MessagingTestApp.TIERED_QUEUE, delay));
            assertThat(tier).as("tier %s declared", delay).isNotNull();
        }
        assertThat(admin.getQueueProperties(DLQ)).isNotNull();
    }

    @Test
    void transientFailureClimbsTheTiersAndCommitsOnce() throws Exception {
        EventEnvelope envelope = publish("tiered.flaky", "flaky");

        await(() -> processed(envelope.eventId()) == 1, 15);
        List<MessagingTestApp.Delivery> seen = consumer.deliveries.get(envelope.eventId().toString());
        assertThat(seen).extracting(MessagingTestApp.Delivery::retryHeader).containsExactly(null, 1, 2);
        assertGapAtLeast(seen, 0, MessagingTestApp.TIERS.get(0));
        assertGapAtLeast(seen, 1, MessagingTestApp.TIERS.get(1));
        assertThat(consumer.attempts.get(envelope.eventId()).get()).isEqualTo(3);
        assertThat(effects(envelope.eventId())).as("failed attempts rolled back their effects").isEqualTo(1);
        assertThat(receiveContaining(DLQ, envelope.eventId(), 1)).isNull();
    }

    @Test
    void persistentFailureUsesEveryTierThenLandsInTheDlq() throws Exception {
        EventEnvelope envelope = publish("tiered.broken", "broken");

        Message dead = receiveContaining(DLQ, envelope.eventId(), 20);
        assertThat(dead).as("dead-lettered after the last tier").isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(envelope.eventId().toString());
        assertThat(((Number) dead.getMessageProperties().getHeaders().get(EventTopology.RETRY_COUNT_HEADER)).intValue())
                .isEqualTo(MessagingTestApp.TIERS.size());
        List<MessagingTestApp.Delivery> seen = consumer.deliveries.get(envelope.eventId().toString());
        assertThat(seen).extracting(MessagingTestApp.Delivery::retryHeader).containsExactly(null, 1, 2, 3);
        for (int i = 0; i < MessagingTestApp.TIERS.size(); i++) {
            assertGapAtLeast(seen, i, MessagingTestApp.TIERS.get(i));
        }
        assertThat(processed(envelope.eventId())).isZero();
        assertThat(effects(envelope.eventId())).isZero();
    }

    @Test
    void poisonMessageSkipsTheTiers() throws Exception {
        String marker = UUID.randomUUID().toString();
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(marker);
        rabbit.send(EventTopology.EXCHANGE, "tiered.poison", new Message(("{not an envelope " + marker).getBytes(StandardCharsets.UTF_8), properties));

        Message dead = receiveContaining(DLQ, marker, 10);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getHeaders()).doesNotContainKey(EventTopology.RETRY_COUNT_HEADER);
        assertThat(consumer.deliveries.get(marker)).hasSize(1);
    }

    @Test
    void duplicateDeliveredWhileARetryIsPendingIsAppliedOnce() throws Exception {
        EventEnvelope envelope = publish("tiered.once", "once");
        String id = envelope.eventId().toString();
        await(() -> consumer.deliveries.containsKey(id) && !consumer.deliveries.get(id).isEmpty(), 10);

        // A broker redelivery or relay re-publish arrives while the first failure waits in the first tier.
        rabbit.send(EventTopology.EXCHANGE, "tiered.once", message(envelope));

        await(() -> consumer.deliveries.get(id).size() == 3, 15);
        Thread.sleep(MessagingTestApp.TIERS.getLast().toMillis() + 500);
        assertThat(consumer.deliveries.get(id)).as("original, duplicate, and the returning retry copy").hasSize(3);
        assertThat(processed(envelope.eventId())).isEqualTo(1);
        assertThat(effects(envelope.eventId())).isEqualTo(1);
        assertThat(consumer.attempts.get(envelope.eventId()).get()).as("the late copy is skipped before the handler").isEqualTo(2);
    }

    @Test
    void redeliveryAfterSuccessIsSkipped() throws Exception {
        EventEnvelope envelope = publish("tiered.ok", "ok");
        await(() -> processed(envelope.eventId()) == 1, 10);

        rabbit.send(EventTopology.EXCHANGE, "tiered.ok", message(envelope));
        await(() -> consumer.deliveries.get(envelope.eventId().toString()).size() == 2, 10);

        assertThat(effects(envelope.eventId())).isEqualTo(1);
        assertThat(processed(envelope.eventId())).isEqualTo(1);
    }

    private EventEnvelope publish(String routingKey, String kind) {
        EventEnvelope envelope = tx.execute(status -> outbox.append(routingKey, "TestHappened", "TEST", "agg-t", null, Map.of("kind", kind)));
        relay.publishPending();
        return envelope;
    }

    private Message message(EventEnvelope envelope) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(envelope.eventId().toString());
        return new Message(objectMapper.writeValueAsBytes(envelope), properties);
    }

    private Message receiveContaining(String queue, Object marker, int seconds) {
        Instant deadline = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(queue, 500);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(marker.toString())) {
                return candidate;
            }
        }
        return null;
    }

    private static void assertGapAtLeast(List<MessagingTestApp.Delivery> seen, int index, Duration delay) {
        Duration gap = Duration.between(seen.get(index).at(), seen.get(index + 1).at());
        assertThat(gap).as("wait before retry %d", index + 1).isGreaterThanOrEqualTo(delay.minusMillis(50));
    }

    private int effects(UUID eventId) {
        return jdbc.queryForObject("select count(*) from effects where event_id = ?", Integer.class, eventId.toString());
    }

    private int processed(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where consumer = 'tiered-consumer' and event_id = ?",
                Integer.class, (Object) Uuids.toBytes(eventId));
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(seconds);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + seconds + "s");
            }
            Thread.sleep(50);
        }
    }
}
