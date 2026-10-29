package com.ifp.notification.messaging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.notification.support.AbstractIntegrationTest;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Delayed retry tiers on notification.events: SMTP failures wait in a tier instead of blocking a listener thread. */
class NotificationRetryIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void consumerQueueHasTheThreeDelayedRetryTiers() {
        for (Duration delay : EventTopology.DEFAULT_RETRY_DELAYS) {
            assertThat(amqpAdmin.getQueueProperties(EventTopology.retryQueueName(NotificationEventsListener.QUEUE, delay)))
                    .as("tier %s", delay).isNotNull();
        }
    }

    /** A broker holding the queue from the old plain declaration must accept the new one unchanged. */
    @Test
    void existingQueueAndDeadLetterQueueDeclarationsAreUnchanged() {
        Declarables before = EventTopology.consumerQueue(NotificationEventsListener.QUEUE, "user.registered", "invoice.*", "financing.*");
        Map<String, Queue> after = new HashMap<>();
        NotificationEventsListener.notificationEventsQueue().getDeclarablesByType(Queue.class).forEach(q -> after.put(q.getName(), q));

        for (Queue old : before.getDeclarablesByType(Queue.class)) {
            assertThat(after).containsKey(old.getName());
            assertThat(after.get(old.getName()).getArguments()).as(old.getName()).isEqualTo(old.getArguments());
            assertThat(after.get(old.getName()).isDurable()).isEqualTo(old.isDurable());
            assertThat(amqpAdmin.declareQueue(old)).as("broker accepts the old declaration of %s", old.getName())
                    .isEqualTo(old.getName());
        }
    }

    /**
     * The first SMTP failure is recorded and the event goes to the 10 s tier; it is attempted again only after that
     * delay (no in-listener retry). Nothing is marked processed while it keeps failing.
     */
    @Test
    void failedSendIsRetriedFromTheFirstTierAfterItsDelay() throws Exception {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publish("user.registered", envelope(eventId, "UserRegistered", "USER", user.toString(), objectMapper.createObjectNode()
                .put("userId", user.toString()).put("email", "broken@@example.test").put("displayName", "Bo").put("role", "SELLER")));

        await(() -> attempts(eventId).size() == 1, 20);
        Thread.sleep(3000);
        assertThat(attempts(eventId)).as("no in-listener retry").hasSize(1);
        await(() -> attempts(eventId).size() >= 2, 25);

        List<Timestamp> times = jdbc.queryForList(
                "select attempted_at from delivery_attempts where event_id = UUID_TO_BIN(?) order by id", Timestamp.class, eventId.toString());
        assertThat(Duration.between(times.get(0).toInstant(), times.get(1).toInstant()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(9));
        assertThat(jdbc.queryForList("select concat(outcome, ':', error) from delivery_attempts where event_id = UUID_TO_BIN(?)",
                String.class, eventId.toString())).allSatisfy(row -> assertThat(row).startsWith("FAILED:Mail"));
        assertThat(count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId)).isZero();
        assertThat(count("select count(*) from processed_events where event_id = UUID_TO_BIN(?)", eventId)).isZero();
    }

    /** A delivery that has used every tier (as when the 10 min tier hands it back) is dead-lettered after one more failure. */
    @Test
    void failureAfterTheLastTierIsDeadLetteredWithItsAttemptOnRecord() throws Exception {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String body = envelope(eventId, "UserRegistered", "USER", user.toString(), objectMapper.createObjectNode()
                .put("userId", user.toString()).put("email", "broken@@example.test").put("displayName", "Bo").put("role", "SELLER"));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        properties.setHeader(EventTopology.RETRY_COUNT_HEADER, EventTopology.DEFAULT_RETRY_DELAYS.size());
        rabbit.send(EventTopology.RETRY_EXCHANGE, NotificationEventsListener.QUEUE, new Message(body.getBytes(StandardCharsets.UTF_8), properties));

        Message dead = receiveContaining(NotificationEventsListener.QUEUE + EventTopology.DLQ_SUFFIX, eventId.toString());
        assertThat(dead).as("dead-lettered after the last tier").isNotNull();
        assertThat(dead.getMessageProperties().getHeaders().get(EventTopology.RETRY_COUNT_HEADER))
                .isEqualTo(EventTopology.DEFAULT_RETRY_DELAYS.size());
        assertThat(attempts(eventId)).hasSize(1).allSatisfy(row -> assertThat(row).startsWith("FAILED:Mail"));
        assertThat(count("select count(*) from processed_events where event_id = UUID_TO_BIN(?)", eventId)).isZero();
    }

    /** A message parked in a tier returns only after the tier's delay; a duplicate delivered directly is applied once. */
    @Test
    void messageWaitingInARetryTierReturnsAfterItsDelayAndIsAppliedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = envelope(eventId, "FinancingOfferRejected", "OFFER", UUID.randomUUID().toString(), objectMapper.createObjectNode()
                .put("sellerId", UUID.randomUUID().toString()).put("financierId", UUID.randomUUID().toString()));
        String tier = EventTopology.retryQueueName(NotificationEventsListener.QUEUE, EventTopology.DEFAULT_RETRY_DELAYS.getFirst());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        properties.setHeader(EventTopology.RETRY_COUNT_HEADER, 1);
        Instant parked = Instant.now();
        rabbit.send(EventTopology.RETRY_EXCHANGE, tier, new Message(body.getBytes(StandardCharsets.UTF_8), properties));

        Thread.sleep(3000);
        assertThat(count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId)).as("still in %s", tier).isZero();
        await(() -> count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId) == 1, 20);
        assertThat(Duration.between(parked, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(9));

        publish("financing.offer_rejected", body);
        Thread.sleep(1500);
        assertThat(count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = ? and event_id = UUID_TO_BIN(?)",
                Integer.class, NotificationEventsListener.CONSUMER, eventId.toString())).isEqualTo(1);
    }

    private List<String> attempts(UUID eventId) {
        return jdbc.queryForList("select concat(outcome, ':', error) from delivery_attempts where event_id = UUID_TO_BIN(?) order by id",
                String.class, eventId.toString());
    }

    private String envelope(UUID eventId, String type, String aggregateType, String aggregateId, ObjectNode payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(eventId, type, EventEnvelope.SCHEMA_VERSION, Instant.now(),
                "test", aggregateType, aggregateId, null, null, payload));
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Message receiveContaining(String queue, String text) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(queue, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(text)) {
                return candidate;
            }
        }
        return null;
    }

    private int count(String sql, UUID eventId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, eventId.toString());
        return value == null ? 0 : value;
    }

    private static void await(Callable<Boolean> condition, int seconds) throws Exception {
        Instant deadline = Instant.now().plusSeconds(seconds);
        while (!condition.call()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + seconds + "s");
            }
            Thread.sleep(200);
        }
    }
}
