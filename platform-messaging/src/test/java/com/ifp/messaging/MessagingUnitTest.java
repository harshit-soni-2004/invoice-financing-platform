package com.ifp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    void retryTiersDeadLetterBackToTheirConsumerQueueOnly() {
        Declarables plain = EventTopology.consumerQueue("svc.events", "invoice.*");
        Declarables tiered = EventTopology.consumerQueueWithRetries("svc.events", "invoice.*");

        Queue plainMain = queue(plain, "svc.events");
        Queue tieredMain = queue(tiered, "svc.events");
        assertThat(tieredMain.getArguments()).as("main queue unchanged, so no redeclaration conflict")
                .isEqualTo(plainMain.getArguments());

        assertThat(tiered.getDeclarablesByType(Queue.class)).extracting(Queue::getName).containsExactlyInAnyOrder(
                "svc.events", "svc.events.dlq", "svc.events.retry.10s", "svc.events.retry.1m", "svc.events.retry.10m");
        Queue first = queue(tiered, "svc.events.retry.10s");
        assertThat(first.getArguments()).containsEntry("x-message-ttl", 10_000)
                .containsEntry("x-dead-letter-exchange", EventTopology.RETRY_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "svc.events");
        assertThat(queue(tiered, "svc.events.retry.10m").getArguments()).containsEntry("x-message-ttl", 600_000);
        assertThat(tiered.getDeclarablesByType(Binding.class))
                .filteredOn(b -> b.getExchange().equals(EventTopology.RETRY_EXCHANGE))
                .extracting(b -> b.getDestination() + "<-" + b.getRoutingKey())
                .containsExactlyInAnyOrder("svc.events<-svc.events", "svc.events.retry.10s<-svc.events.retry.10s",
                        "svc.events.retry.1m<-svc.events.retry.1m", "svc.events.retry.10m<-svc.events.retry.10m");
    }

    @Test
    void retryTierDelaysAreValidated() {
        assertThatThrownBy(() -> EventTopology.consumerQueueWithRetries("q", List.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventTopology.consumerQueueWithRetries("q", List.of(Duration.ZERO), "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventTopology.consumerQueueWithRetries("q", List.of(Duration.ofSeconds(1), Duration.ofMillis(1000)), "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EventTopology.label(Duration.ofMillis(1500))).isEqualTo("1500ms");
        assertThat(EventTopology.label(Duration.ofHours(2))).isEqualTo("2h");
    }

    @Test
    void tiersAreDiscoveredFromQueueArgumentsInDelayOrder() {
        Map<String, List<DelayedRetryAdvice.Tier>> tiers = DelayedRetryAdvice.discover(List.of(
                EventTopology.consumerQueueWithRetries("a", List.of(Duration.ofMinutes(5), Duration.ofSeconds(2)), "x"),
                EventTopology.consumerQueue("b", "y")), List.of());

        assertThat(tiers).containsOnlyKeys("a");
        assertThat(tiers.get("a")).extracting(DelayedRetryAdvice.Tier::delay)
                .containsExactly(Duration.ofSeconds(2), Duration.ofMinutes(5));
        assertThat(tiers.get("a")).extracting(DelayedRetryAdvice.Tier::queue).containsExactly("a.retry.2s", "a.retry.5m");
    }

    @Test
    void retryCopyKeepsIdentityDropsDeathHeadersAndCountsRetries() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("evt-1");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setType("TestHappened");
        properties.setHeader("x-death", List.of(Map.of("count", 1L)));
        properties.setHeader("x-first-death-queue", "q.retry.10s");
        properties.setHeader("custom", "kept");
        properties.setHeader(EventTopology.RETRY_COUNT_HEADER, 1);
        Message original = new Message("{}".getBytes(StandardCharsets.UTF_8), properties);

        assertThat(DelayedRetryAdvice.retryCount(original)).isEqualTo(1);
        Message copy = DelayedRetryAdvice.retryCopy(original, 2);

        assertThat(copy.getMessageProperties().getMessageId()).isEqualTo("evt-1");
        assertThat(copy.getMessageProperties().getType()).isEqualTo("TestHappened");
        assertThat(copy.getMessageProperties().getHeaders()).containsEntry("custom", "kept")
                .containsEntry(EventTopology.RETRY_COUNT_HEADER, 2)
                .doesNotContainKeys("x-death", "x-first-death-queue");
        assertThat(DelayedRetryAdvice.retryCount(new Message(new byte[0], new MessageProperties()))).isZero();
        MessageProperties text = new MessageProperties();
        text.setHeader(EventTopology.RETRY_COUNT_HEADER, "3");
        assertThat(DelayedRetryAdvice.retryCount(new Message(new byte[0], text))).isEqualTo(3);
    }

    @Test
    void onlyRejectionsAndConversionErrorsAreTreatedAsPermanent() {
        assertThat(DelayedRetryAdvice.isPermanent(new ListenerExecutionFailedException("x", new AmqpRejectAndDontRequeueException("bad")))).isTrue();
        assertThat(DelayedRetryAdvice.isPermanent(new ListenerExecutionFailedException("x", new MessageConversionException("bad")))).isTrue();
        assertThat(DelayedRetryAdvice.isPermanent(new ListenerExecutionFailedException("x", new IllegalStateException("db down")))).isFalse();
    }

    @Test
    void processedEventsRetentionRejectsUnsafeSettings() {
        assertThatThrownBy(() -> new MessagingProperties.ProcessedEvents(true, 0, Duration.ofHours(1), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagingProperties.ProcessedEvents(true, 30, Duration.ofHours(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processedEventsPurgeIsANoOpWithoutTheTable() {
        int[] deletes = {0};
        JdbcTemplate noTable = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                return requiredType.cast(0);
            }

            @Override
            public int update(String sql, Object... args) {
                deletes[0]++;
                return 0;
            }
        };
        ProcessedEventsPurger purger = new ProcessedEventsPurger(noTable, Clock.systemUTC(),
                new MessagingProperties.ProcessedEvents(true, 30, Duration.ofHours(1), 100), new SimpleMeterRegistry());

        assertThat(purger.purgeExpired()).isZero();
        assertThat(purger.purgeExpired()).isZero();
        assertThat(deletes[0]).isZero();
    }

    private static Queue queue(Declarables declarables, String name) {
        return declarables.getDeclarablesByType(Queue.class).stream().filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
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
