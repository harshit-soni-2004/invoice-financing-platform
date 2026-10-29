package com.ifp.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Listener advice that replaces in-listener retries for queues declared with
 * {@link EventTopology#consumerQueueWithRetries}. A failed delivery is republished (with publisher confirm) to the
 * next delay tier with {@value EventTopology#RETRY_COUNT_HEADER} incremented and the original is acknowledged; the
 * tier queue's TTL then dead-letters it back to the consumer queue. Once every tier has been used, or for failures
 * that can never succeed (malformed or rejected messages), the delivery is rejected without requeue so the broker
 * dead-letters it to {@code <queue>.dlq}.
 * <p>
 * Republish-then-ack is at-least-once: a crash between the two leaves both copies, and the idempotent consumer
 * ({@code processed_events}) applies the event once. Queues without tiers are untouched: the failure propagates to
 * whatever the container does today (Boot's stateless retry, then the dead-letter queue).
 */
public class DelayedRetryAdvice implements MethodInterceptor {

    public record Tier(String queue, Duration delay) {
    }

    private static final Logger log = LoggerFactory.getLogger(DelayedRetryAdvice.class);

    private final Supplier<Map<String, List<Tier>>> tiers;
    private final Supplier<RabbitTemplate> rabbit;
    private final Duration confirmTimeout;
    private final Supplier<MeterRegistry> meters;

    public DelayedRetryAdvice(Supplier<Map<String, List<Tier>>> tiers, Supplier<RabbitTemplate> rabbit,
                              Duration confirmTimeout, Supplier<MeterRegistry> meters) {
        this.tiers = tiers;
        this.rabbit = rabbit;
        this.confirmTimeout = confirmTimeout;
        this.meters = meters;
    }

    /** Tier queues are recognised by their broker arguments: they dead-letter to the retry exchange. */
    public static Map<String, List<Tier>> discover(Collection<Declarables> declarables, Collection<Queue> queues) {
        List<Queue> all = new ArrayList<>(queues);
        declarables.forEach(d -> all.addAll(d.getDeclarablesByType(Queue.class)));
        Map<String, List<Tier>> result = new HashMap<>();
        for (Queue queue : all) {
            Map<String, Object> args = queue.getArguments();
            if (EventTopology.RETRY_EXCHANGE.equals(args.get("x-dead-letter-exchange"))
                    && args.get("x-dead-letter-routing-key") instanceof String main
                    && args.get("x-message-ttl") instanceof Number ttl) {
                List<Tier> list = result.computeIfAbsent(main, k -> new ArrayList<>());
                if (list.stream().noneMatch(t -> t.queue().equals(queue.getName()))) {
                    list.add(new Tier(queue.getName(), Duration.ofMillis(ttl.longValue())));
                }
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(Tier::delay)));
        result.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(result);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (Throwable failure) {
            Object[] arguments = invocation.getArguments();
            if (arguments.length < 2 || !(arguments[1] instanceof Message message)) {
                throw failure;
            }
            String queue = message.getMessageProperties().getConsumerQueue();
            List<Tier> ladder = queue == null ? null : tiers.get().get(queue);
            if (ladder == null || ladder.isEmpty() || isPermanent(failure)) {
                throw failure;
            }
            int retries = retryCount(message);
            String id = message.getMessageProperties().getMessageId();
            if (retries >= ladder.size()) {
                meters.get().counter("ifp.messaging.retry.exhausted", "queue", queue).increment();
                log.error("Message {} on {} failed after {} delayed retries ({}); dead-lettering",
                        id, queue, retries, rootCause(failure));
                throw reject(message, "Delayed retries exhausted", failure);
            }
            Tier next = ladder.get(retries);
            String publishFailure = republish(message, next, retries + 1);
            if (publishFailure != null) {
                log.error("Message {} on {} could not be scheduled for retry {} ({}); dead-lettering",
                        id, queue, retries + 1, publishFailure);
                throw reject(message, "Retry publish failed: " + publishFailure, failure);
            }
            meters.get().counter("ifp.messaging.retry.scheduled", "queue", queue, "tier", String.valueOf(retries + 1)).increment();
            log.warn("Message {} on {} failed ({}); retry {} of {} in {} via {}", id, queue, rootCause(failure),
                    retries + 1, ladder.size(), next.delay(), next.queue());
            return null;
        }
    }

    static boolean isPermanent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AmqpRejectAndDontRequeueException || t instanceof MessageConversionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    static int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(EventTopology.RETRY_COUNT_HEADER);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Copy for the next tier. Broker-maintained death headers are dropped; the retry count travels in our header. */
    static Message retryCopy(Message message, int retryCount) {
        MessageProperties source = message.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setContentType(source.getContentType());
        target.setContentEncoding(source.getContentEncoding());
        target.setMessageId(source.getMessageId());
        target.setType(source.getType());
        target.setCorrelationId(source.getCorrelationId());
        target.setTimestamp(source.getTimestamp());
        target.setAppId(source.getAppId());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        source.getHeaders().forEach((key, value) -> {
            if (!key.equals("x-death") && !key.startsWith("x-first-death-") && !key.startsWith("x-last-death-")) {
                target.setHeader(key, value);
            }
        });
        target.setHeader(EventTopology.RETRY_COUNT_HEADER, retryCount);
        return new Message(message.getBody(), target);
    }

    /** Returns null on a broker ack, otherwise a short reason. */
    private String republish(Message message, Tier tier, int retryCount) {
        RabbitTemplate template = rabbit.get();
        Message copy = retryCopy(message, retryCount);
        try {
            if (!template.getConnectionFactory().isPublisherConfirms()) {
                template.send(EventTopology.RETRY_EXCHANGE, tier.queue(), copy);
                return null;
            }
            CorrelationData correlation = new CorrelationData();
            template.send(EventTopology.RETRY_EXCHANGE, tier.queue(), copy, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return confirm.isAck() ? null : "NACK";
        } catch (java.util.concurrent.TimeoutException e) {
            return "CONFIRM_TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private static ListenerExecutionFailedException reject(Message message, String reason, Throwable failure) {
        return new ListenerExecutionFailedException(reason, new AmqpRejectAndDontRequeueException(reason, failure), message);
    }

    private static String rootCause(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
