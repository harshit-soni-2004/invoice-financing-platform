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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Broker topology shared by every service. All services declare it identically (RabbitMQ rejects re-declarations
 * with different arguments), so the definitions live only here.
 * <ul>
 *   <li>{@value #EXCHANGE}: durable topic exchange, routing key {@code <aggregate>.<event>}.</li>
 *   <li>{@value #UNROUTED_EXCHANGE} / {@value #UNROUTED_QUEUE}: alternate exchange, so events published before any
 *       consumer queue is bound are parked instead of silently dropped.</li>
 *   <li>{@value #DEAD_LETTER_EXCHANGE}: each consumer queue dead-letters to {@code <queue>.dlq} with routing key
 *       {@code <queue>}.</li>
 * </ul>
 */
public final class EventTopology {

    public static final String EXCHANGE = "ifp.events";
    public static final String UNROUTED_EXCHANGE = "ifp.events.unrouted";
    public static final String UNROUTED_QUEUE = "ifp.events.unrouted";
    public static final String DEAD_LETTER_EXCHANGE = "ifp.events.dlx";
    public static final String DLQ_SUFFIX = ".dlq";

    private EventTopology() {
    }

    static Declarables shared() {
        TopicExchange events = new TopicExchange(EXCHANGE, true, false, Map.of("alternate-exchange", UNROUTED_EXCHANGE));
        FanoutExchange unrouted = new FanoutExchange(UNROUTED_EXCHANGE, true, false);
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED_QUEUE).build();
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        return new Declarables(events, unrouted, unroutedQueue, BindingBuilder.bind(unroutedQueue).to(unrouted), deadLetters);
    }

    /** A durable consumer queue bound to the given routing keys, with its dead-letter queue. */
    public static Declarables consumerQueue(String queue, String... routingKeys) {
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
        return new Declarables(declarables);
    }
}
