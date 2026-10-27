package com.ifp.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox rows to {@link EventTopology#EXCHANGE} with publisher confirms. A row is marked
 * published only after the broker acknowledges it; otherwise it is retried with exponential backoff. Rows are
 * claimed with {@code FOR UPDATE SKIP LOCKED}, so several instances can relay the same outbox without
 * double-publishing in the normal case. Delivery is at-least-once: a crash between the broker ack and the commit
 * republishes the row, and consumers deduplicate by event ID.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final MessagingProperties.Relay settings;
    private volatile ScheduledExecutorService executor;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, RabbitTemplate rabbit, Clock clock,
                       MessagingProperties.Relay settings) {
        if (!rabbit.getConnectionFactory().isPublisherConfirms()) {
            throw new IllegalStateException("The outbox relay needs spring.rabbitmq.publisher-confirm-type=correlated");
        }
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        this.clock = clock;
        this.settings = settings;
    }

    private record Row(UUID id, String routingKey, String eventType, String envelope, int attempts) {
    }

    /** One relay pass. Returns the number of rows published; also used by tests to drain the outbox. */
    public int publishPending() {
        Integer published = tx.execute(status -> {
            Instant now = clock.instant();
            List<Row> rows = jdbc.query("""
                            select id, routing_key, event_type, envelope, attempts from outbox_events
                            where published_at is null and next_attempt_at <= ?
                            order by seq
                            limit ?
                            for update skip locked""",
                    (rs, n) -> new Row(Uuids.fromBytes(rs.getBytes("id")), rs.getString("routing_key"),
                            rs.getString("event_type"), rs.getString("envelope"), rs.getInt("attempts")),
                    Timestamp.from(now), settings.batchSize());
            int ok = 0;
            for (Row row : rows) {
                String failure = publish(row);
                if (failure == null) {
                    jdbc.update("update outbox_events set published_at = ?, last_error = null where id = ?",
                            Timestamp.from(clock.instant()), Uuids.toBytes(row.id()));
                    ok++;
                } else {
                    int attempts = row.attempts() + 1;
                    Instant retryAt = clock.instant().plus(backoff(attempts, settings.maxBackoff()));
                    jdbc.update("update outbox_events set attempts = ?, next_attempt_at = ?, last_error = ? where id = ?",
                            attempts, Timestamp.from(retryAt), failure, Uuids.toBytes(row.id()));
                    log.warn("Outbox event {} ({}) not published, attempt {}: {}", row.id(), row.eventType(), attempts, failure);
                }
            }
            return ok;
        });
        return published == null ? 0 : published;
    }

    /** Returns null on a broker ack, otherwise a short reason (never message content). */
    private String publish(Row row) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(row.id().toString());
        properties.setType(row.eventType());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(row.envelope().getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(row.id().toString());
        try {
            rabbit.send(EventTopology.EXCHANGE, row.routingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(settings.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
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

    /** 2, 4, 8 ... seconds, capped. */
    static Duration backoff(int attempts, Duration max) {
        long seconds = attempts >= 30 ? Long.MAX_VALUE : 1L << Math.max(1, attempts);
        Duration delay = Duration.ofSeconds(Math.min(seconds, max.toSeconds()));
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private void pass() {
        try {
            int published;
            do {
                published = publishPending();
            } while (published == settings.batchSize());
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long interval = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pass, interval, interval, TimeUnit.MILLISECONDS);
        executor = scheduler;
    }

    @Override
    public void stop() {
        ScheduledExecutorService scheduler = executor;
        executor = null;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
