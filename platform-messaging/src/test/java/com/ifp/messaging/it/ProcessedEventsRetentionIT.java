package com.ifp.messaging.it;

import com.ifp.messaging.EventTopology;
import com.ifp.messaging.MessagingProperties;
import com.ifp.messaging.ProcessedEventsPurger;
import com.ifp.messaging.Uuids;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MessagingTestApp.class)
class ProcessedEventsRetentionIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessedEventsPurger configuredPurger;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private MessagingTestApp.TieredConsumer consumer;

    @Test
    void deletesOnlyRowsOlderThanTheRetentionInBatchesAndCountsThem() {
        String consumerName = "retention-" + UUID.randomUUID().toString().substring(0, 8);
        UUID old1 = insert(consumerName, Duration.ofDays(31));
        UUID old2 = insert(consumerName, Duration.ofDays(45));
        UUID old3 = insert(consumerName, Duration.ofDays(400));
        UUID recent = insert(consumerName, Duration.ofDays(29));
        UUID fresh = insert(consumerName, Duration.ZERO);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProcessedEventsPurger purger = new ProcessedEventsPurger(jdbc, Clock.systemUTC(),
                new MessagingProperties.ProcessedEvents(true, 30, Duration.ofHours(1), 2), meters);

        int deleted = purger.purgeExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(exists(consumerName, old1)).isFalse();
        assertThat(exists(consumerName, old2)).isFalse();
        assertThat(exists(consumerName, old3)).isFalse();
        assertThat(exists(consumerName, recent)).as("inside the retention period").isTrue();
        assertThat(exists(consumerName, fresh)).isTrue();
        assertThat(meters.counter("ifp.messaging.processed.events.purged").count()).isEqualTo(deleted);
        assertThat(purger.purgeExpired()).as("second pass finds nothing").isZero();
    }

    @Test
    void defaultRetentionIsThirtyDays() {
        String consumerName = "retention-" + UUID.randomUUID().toString().substring(0, 8);
        UUID thirtyOneDays = insert(consumerName, Duration.ofDays(31));
        UUID twentyNineDays = insert(consumerName, Duration.ofDays(29));

        configuredPurger.purgeExpired();

        assertThat(exists(consumerName, thirtyOneDays)).isFalse();
        assertThat(exists(consumerName, twentyNineDays)).isTrue();
    }

    /**
     * The documented trade-off: once its row is purged, a very late duplicate is processed again. Inside the
     * retention it is still skipped.
     */
    @Test
    void lateDuplicateIsSkippedInsideTheRetentionButNotAfterThePurge() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"TestHappened","schemaVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
                 "producer":"p","aggregateType":"TEST","aggregateId":"r","payload":{"kind":"ok"}}""".formatted(eventId);
        send(body, eventId);
        awaitEffects(eventId, 1);

        jdbc.update("update processed_events set processed_at = ? where consumer = 'tiered-consumer' and event_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(29))), Uuids.toBytes(eventId));
        configuredPurger.purgeExpired();
        send(body, eventId);
        awaitDeliveries(eventId, 2);
        assertThat(effects(eventId)).as("still de-duplicated inside the retention").isEqualTo(1);

        jdbc.update("update processed_events set processed_at = ? where consumer = 'tiered-consumer' and event_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))), Uuids.toBytes(eventId));
        configuredPurger.purgeExpired();
        send(body, eventId);
        awaitEffects(eventId, 2);
    }

    private void send(String body, UUID eventId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        rabbit.send(EventTopology.EXCHANGE, "tiered.ok", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private UUID insert(String consumerName, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into processed_events (consumer, event_id, event_type, processed_at) values (?, ?, 'X', ?)",
                consumerName, Uuids.toBytes(id), Timestamp.from(Instant.now().minus(age)));
        return id;
    }

    private boolean exists(String consumerName, UUID id) {
        return jdbc.queryForObject("select count(*) from processed_events where consumer = ? and event_id = ?",
                Integer.class, consumerName, Uuids.toBytes(id)) == 1;
    }

    private int effects(UUID eventId) {
        return jdbc.queryForObject("select count(*) from effects where event_id = ?", Integer.class, eventId.toString());
    }

    private void awaitEffects(UUID eventId, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (effects(eventId) != expected) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("expected " + expected + " effects, saw " + effects(eventId));
            }
            Thread.sleep(50);
        }
    }

    private void awaitDeliveries(UUID eventId, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (consumer.deliveries.getOrDefault(eventId.toString(), java.util.List.of()).size() < expected) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("expected " + expected + " deliveries");
            }
            Thread.sleep(50);
        }
        Thread.sleep(300);
    }
}
