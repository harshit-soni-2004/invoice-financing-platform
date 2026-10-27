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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal service used to exercise the module end to end against real MySQL and RabbitMQ. */
@SpringBootApplication
public class MessagingTestApp {

    static final String QUEUE = "test.events";

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("messaging_test")
                .withCommand("--default-time-zone=+00:00");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
    }

    @Bean
    Declarables testQueue() {
        return EventTopology.consumerQueue(QUEUE, "test.#");
    }

    /**
     * Records a business effect per event ID. The routing key's last segment drives failure injection:
     * {@code test.flaky} fails twice then succeeds, {@code test.broken} always fails.
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
}
