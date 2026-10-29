package com.ifp.messaging.it;

import com.ifp.messaging.EventTopology;
import com.ifp.messaging.InboundEventProcessor;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal service used to exercise the module end to end against real MySQL and RabbitMQ. */
@SpringBootApplication
public class MessagingTestApp {

    static final String QUEUE = "test.events";
    static final String TIERED_QUEUE = "test.tiered";
    static final List<Duration> TIERS = List.of(Duration.ofMillis(300), Duration.ofMillis(600), Duration.ofMillis(900));

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("messaging_test")
                .withCommand("--default-time-zone=+00:00");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        // A loaded Docker host can take over a minute to boot RabbitMQ; the default wait is 60 s.
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    /** Uses the in-listener retry configured in application.yml (no delay tiers). */
    @Bean
    Declarables testQueue() {
        return EventTopology.consumerQueue(QUEUE, "test.#");
    }

    /** Same consumer contract, but with short delayed retry tiers instead of in-listener retries. */
    @Bean
    Declarables tieredQueue() {
        return EventTopology.consumerQueueWithRetries(TIERED_QUEUE, TIERS, "tiered.#");
    }

    /**
     * Records a business effect per event ID. The payload's {@code kind} drives failure injection:
     * {@code flaky} fails twice then succeeds, {@code once} fails only the first time, {@code broken} always fails.
     */
    @Component
    static class TestConsumer {

        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final InboundEventProcessor processor;
        private final JdbcTemplate jdbc;

        TestConsumer(InboundEventProcessor processor, JdbcTemplate jdbc) {
            this.processor = processor;
            this.jdbc = jdbc;
        }

        @RabbitListener(queues = QUEUE)
        void onMessage(Message message) {
            processor.process(message, "test-consumer", envelope -> {
                int attempt = attempts.computeIfAbsent(envelope.eventId(), id -> new AtomicInteger()).incrementAndGet();
                jdbc.update("insert into effects (event_id) values (?)", envelope.eventId().toString());
                String kind = envelope.payload().path("kind").asText();
                if (kind.equals("broken") || (kind.equals("flaky") && attempt <= 2)) {
                    throw new IllegalStateException("simulated failure " + attempt);
                }
            });
        }
    }

    /** One delivery as the tiered consumer saw it. */
    record Delivery(Instant at, Object retryHeader) {
    }

    @Component
    static class TieredConsumer {

        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Map<String, List<Delivery>> deliveries = new ConcurrentHashMap<>();
        private final InboundEventProcessor processor;
        private final JdbcTemplate jdbc;

        TieredConsumer(InboundEventProcessor processor, JdbcTemplate jdbc) {
            this.processor = processor;
            this.jdbc = jdbc;
        }

        @RabbitListener(queues = TIERED_QUEUE)
        void onMessage(Message message) {
            String messageId = String.valueOf(message.getMessageProperties().getMessageId());
            deliveries.computeIfAbsent(messageId, id -> new CopyOnWriteArrayList<>())
                    .add(new Delivery(Instant.now(), message.getMessageProperties().getHeaders().get(EventTopology.RETRY_COUNT_HEADER)));
            processor.process(message, "tiered-consumer", envelope -> {
                int attempt = attempts.computeIfAbsent(envelope.eventId(), id -> new AtomicInteger()).incrementAndGet();
                jdbc.update("insert into effects (event_id) values (?)", envelope.eventId().toString());
                String kind = envelope.payload().path("kind").asText();
                if (kind.equals("broken") || (kind.equals("flaky") && attempt <= 2) || (kind.equals("once") && attempt == 1)) {
                    throw new IllegalStateException("simulated failure " + attempt);
                }
            });
        }
    }
}
