package com.ifp.messaging.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.MessagingProperties;
import com.ifp.messaging.OutboxRelay;
import com.ifp.messaging.OutboxWriter;
import com.ifp.messaging.Uuids;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = MessagingTestApp.class)
class MessagingIT {

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
    private ObjectMapper objectMapper;
    @Autowired
    private MessagingTestApp.TestConsumer consumer;

    @Test
    void committedEventIsPublishedConsumedOnceAndDuplicatesAreSkipped() throws Exception {
        EventEnvelope envelope = tx.execute(status -> outbox.append("test.ok", "TestHappened", "TEST", "agg-1", null, Map.of("kind", "ok")));

        assertThat(relay.publishPending()).isGreaterThanOrEqualTo(1);
        await(() -> effects(envelope.eventId()) == 1);
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where id = ?", Boolean.class,
                (Object) Uuids.toBytes(envelope.eventId()))).isTrue();

        // Broker redelivery or a relay crash after the ack: the same event arrives again.
        rabbit.send(EventTopology.EXCHANGE, "test.ok", message(objectMapper.writeValueAsString(envelope)));
        Thread.sleep(1000);
        assertThat(effects(envelope.eventId())).isEqualTo(1);
        assertThat(processed(envelope.eventId())).isEqualTo(1);
    }

    @Test
    void rolledBackBusinessTransactionLeavesNoEvent() {
        UUID[] id = new UUID[1];
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            id[0] = outbox.append("test.ok", "TestHappened", "TEST", "agg-2", null, Map.of("kind", "ok")).eventId();
            throw new IllegalStateException("business failure after the event was written");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class,
                (Object) Uuids.toBytes(id[0]))).isZero();
    }

    @Test
    void writingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.append("test.ok", "TestHappened", "TEST", "agg-3", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transientFailuresAreRetriedAndTheEffectCommitsOnce() throws Exception {
        EventEnvelope envelope = tx.execute(status -> outbox.append("test.flaky", "TestHappened", "TEST", "agg-4", null, Map.of("kind", "flaky")));
        relay.publishPending();

        await(() -> processed(envelope.eventId()) == 1);
        assertThat(consumer.attempts.get(envelope.eventId()).get()).isEqualTo(3);
        assertThat(effects(envelope.eventId())).as("failed attempts rolled back their effects").isEqualTo(1);
    }

    @Test
    void persistentFailureIsDeadLetteredAfterBoundedRetries() throws Exception {
        EventEnvelope envelope = tx.execute(status -> outbox.append("test.broken", "TestHappened", "TEST", "agg-5", null, Map.of("kind", "broken")));
        relay.publishPending();

        Message dead = rabbit.receive(MessagingTestApp.QUEUE + EventTopology.DLQ_SUFFIX, 15_000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(envelope.eventId().toString());
        assertThat(consumer.attempts.get(envelope.eventId()).get()).isEqualTo(3);
        assertThat(processed(envelope.eventId())).isZero();
        assertThat(effects(envelope.eventId())).isZero();
    }

    @Test
    void poisonMessageGoesStraightToTheDeadLetterQueue() {
        rabbit.send(EventTopology.EXCHANGE, "test.poison", message("{this is not an envelope"));

        Message dead = rabbit.receive(MessagingTestApp.QUEUE + EventTopology.DLQ_SUFFIX, 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).startsWith("{this is not");
    }

    @Test
    void eventsWithoutAConsumerAreParkedNotDropped() throws Exception {
        EventEnvelope envelope = tx.execute(status -> outbox.append("nobody.listens", "Orphan", "TEST", "agg-6", null, Map.of()));
        relay.publishPending();

        Message parked = rabbit.receive(EventTopology.UNROUTED_QUEUE, 10_000);
        assertThat(parked).isNotNull();
        assertThat(parked.getMessageProperties().getMessageId()).isEqualTo(envelope.eventId().toString());
    }

    @Test
    void unreachableBrokerKeepsTheRowForRetryWithBackoff() {
        EventEnvelope envelope = tx.execute(status -> outbox.append("test.ok", "TestHappened", "TEST", "agg-7", null, Map.of("kind", "ok")));
        CachingConnectionFactory deadBroker = new CachingConnectionFactory("127.0.0.1", 1);
        deadBroker.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        try {
            OutboxRelay failing = new OutboxRelay(jdbc, tx, new RabbitTemplate(deadBroker), Clock.systemUTC(),
                    new MessagingProperties.Relay(true, Duration.ofHours(1), 50, Duration.ofSeconds(2), Duration.ofMinutes(5)));
            Instant before = Instant.now();

            failing.publishPending();

            Map<String, Object> row = jdbc.queryForMap("""
                            select attempts, published_at, next_attempt_at > ? as deferred, last_error
                            from outbox_events where id = ?""",
                    java.sql.Timestamp.from(before), Uuids.toBytes(envelope.eventId()));
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("published_at")).isNull();
            assertThat(((Number) row.get("deferred")).intValue()).isEqualTo(1);
            assertThat((String) row.get("last_error")).isNotBlank();
        } finally {
            deadBroker.destroy();
        }
        // The healthy relay picks it up once the retry time has passed.
        jdbc.update("update outbox_events set next_attempt_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                Uuids.toBytes(envelope.eventId()));
        relay.publishPending();
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where id = ?", Boolean.class,
                (Object) Uuids.toBytes(envelope.eventId()))).isTrue();
    }

    private int effects(UUID eventId) {
        return jdbc.queryForObject("select count(*) from effects where event_id = ?", Integer.class, eventId.toString());
    }

    private int processed(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class,
                (Object) Uuids.toBytes(eventId));
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 15s");
            }
            Thread.sleep(100);
        }
    }
}
