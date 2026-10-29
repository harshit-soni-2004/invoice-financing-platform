package com.ifp.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Broker topology shared by every service. All services declare it identically (RabbitMQ rejects re-declarations
 * with different arguments), so the definitions live only here.
 * <ul>
 *   <li>{@value #EXCHANGE}: durable topic exchange, routing key {@code <aggregate>.<event>}.</li>
 *   <li>{@value #UNROUTED_EXCHANGE} / {@value #UNROUTED_QUEUE}: alternate exchange, so events published before any
 *       consumer queue is bound are parked instead of silently dropped.</li>
 *   <li>{@value #DEAD_LETTER_EXCHANGE}: each consumer queue dead-letters to {@code <queue>.dlq} with routing key
 *       {@code <queue>}.</li>
 *   <li>{@value #RETRY_EXCHANGE}: delayed retries for queues declared with
 *       {@link #consumerQueueWithRetries(String, List, String...)}. A failed message is published here with routing
 *       key {@code <queue>.retry.<delay>}; that tier queue holds it for its TTL and then dead-letters it back through
 *       this exchange with routing key {@code <queue>}, which is bound to the consumer queue only.</li>
 * </ul>
 */
public final class EventTopology {

    public static final String EXCHANGE = "ifp.events";
    public static final String UNROUTED_EXCHANGE = "ifp.events.unrouted";
    public static final String UNROUTED_QUEUE = "ifp.events.unrouted";
    public static final String DEAD_LETTER_EXCHANGE = "ifp.events.dlx";
    public static final String DLQ_SUFFIX = ".dlq";
    public static final String RETRY_EXCHANGE = "ifp.events.retry";
    public static final String RETRY_SUFFIX = ".retry.";
    /** Number of delayed retries a message has already been through; absent on the first delivery. */
    public static final String RETRY_COUNT_HEADER = "x-ifp-retry-count";
    public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10));

    private EventTopology() {
    }

    static Declarables shared() {
        TopicExchange events = new TopicExchange(EXCHANGE, true, false, Map.of("alternate-exchange", UNROUTED_EXCHANGE));
        FanoutExchange unrouted = new FanoutExchange(UNROUTED_EXCHANGE, true, false);
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED_QUEUE).build();
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        DirectExchange retries = new DirectExchange(RETRY_EXCHANGE, true, false, Map.of("alternate-exchange", UNROUTED_EXCHANGE));
        return new Declarables(events, unrouted, unroutedQueue, BindingBuilder.bind(unroutedQueue).to(unrouted), deadLetters,
                retries);
    }

    /** A durable consumer queue bound to the given routing keys, with its dead-letter queue. */
    public static Declarables consumerQueue(String queue, String... routingKeys) {
        return new Declarables(baseDeclarables(queue, routingKeys));
    }

    /** {@link #consumerQueue} plus the default delayed retry tiers (10 s, 1 min, 10 min) before the dead-letter queue. */
    public static Declarables consumerQueueWithRetries(String queue, String... routingKeys) {
        return consumerQueueWithRetries(queue, DEFAULT_RETRY_DELAYS, routingKeys);
    }

    /**
     * {@link #consumerQueue} plus one delayed retry tier per delay, tried in the given order. The main queue keeps
     * exactly the arguments of {@link #consumerQueue}, so an existing queue can switch to delayed retries without
     * being redeclared. Tier names include the delay, so changing a delay declares a new tier queue instead of
     * conflicting with the old one (which can be deleted once empty).
     */
    public static Declarables consumerQueueWithRetries(String queue, List<Duration> retryDelays, String... routingKeys) {
        if (retryDelays.isEmpty()) {
            throw new IllegalArgumentException("At least one retry delay is required");
        }
        List<Declarable> declarables = baseDeclarables(queue, routingKeys);
        declarables.add(new Binding(queue, Binding.DestinationType.QUEUE, RETRY_EXCHANGE, queue, null));
        Set<String> names = new HashSet<>();
        for (Duration delay : retryDelays) {
            if (delay.isNegative() || delay.isZero() || delay.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Retry delay out of range: " + delay);
            }
            String name = retryQueueName(queue, delay);
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate retry delay " + delay + " for " + queue);
            }
            declarables.add(QueueBuilder.durable(name)
                    .ttl((int) delay.toMillis())
                    .deadLetterExchange(RETRY_EXCHANGE)
                    .deadLetterRoutingKey(queue)
                    .build());
            declarables.add(new Binding(name, Binding.DestinationType.QUEUE, RETRY_EXCHANGE, name, null));
        }
        return new Declarables(declarables);
    }

    public static String retryQueueName(String queue, Duration delay) {
        return queue + RETRY_SUFFIX + label(delay);
    }

    static String label(Duration delay) {
        long millis = delay.toMillis();
        if (millis % 3_600_000 == 0) {
            return millis / 3_600_000 + "h";
        }
        if (millis % 60_000 == 0) {
            return millis / 60_000 + "m";
        }
        if (millis % 1_000 == 0) {
            return millis / 1_000 + "s";
        }
        return millis + "ms";
    }

    private static List<Declarable> baseDeclarables(String queue, String... routingKeys) {
        Queue main = QueueBuilder.durable(queue)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(queue)
                .build();
        Queue dlq = QueueBuilder.durable(queue + DLQ_SUFFIX).build();
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(main);
        declarables.add(dlq);
        declarables.add(new Binding(dlq.getName(), Binding.DestinationType.QUEUE, DEAD_LETTER_EXCHANGE, queue, null));
        for (String key : routingKeys) {
            declarables.add(new Binding(queue, Binding.DestinationType.QUEUE, EXCHANGE, key, null));
        }
        return declarables;
    }
}
