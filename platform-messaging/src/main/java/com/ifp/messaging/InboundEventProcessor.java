package com.ifp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.function.Consumer;

/**
 * Idempotent consumption: the {@code processed_events} row and the handler's business effect commit in one
 * transaction, so a redelivered event (same event ID, same consumer) is skipped. Malformed messages and unknown
 * schema versions are rejected straight to the dead-letter queue; any other failure propagates so the queue's
 * delayed retry tiers ({@link DelayedRetryAdvice}) or the listener's bounded retry can try again before
 * dead-lettering.
 */
public class InboundEventProcessor {

    public enum Result { PROCESSED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InboundEventProcessor(ObjectMapper objectMapper, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public EventEnvelope parse(Message message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Malformed event envelope: " + e.getClass().getSimpleName());
        }
        if (envelope.schemaVersion() != EventEnvelope.SCHEMA_VERSION) {
            throw new AmqpRejectAndDontRequeueException("Unsupported schema version " + envelope.schemaVersion());
        }
        return envelope;
    }

    public Result process(Message message, String consumer, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = parse(message);
        String previous = MDC.get(CorrelationId.MDC_KEY);
        if (CorrelationId.isValid(envelope.correlationId())) {
            MDC.put(CorrelationId.MDC_KEY, envelope.correlationId());
        }
        try {
            Result result = tx.execute(status -> {
                try {
                    jdbc.update("insert into processed_events (consumer, event_id, event_type, processed_at) values (?, ?, ?, ?)",
                            consumer, Uuids.toBytes(envelope.eventId()), envelope.eventType(), Timestamp.from(clock.instant()));
                } catch (DuplicateKeyException duplicate) {
                    return Result.DUPLICATE;
                }
                handler.accept(envelope);
                return Result.PROCESSED;
            });
            if (result == Result.DUPLICATE) {
                log.info("Skipped duplicate event {} ({}) for {}", envelope.eventId(), envelope.eventType(), consumer);
            } else {
                log.info("Processed event {} ({}) for {}", envelope.eventId(), envelope.eventType(), consumer);
            }
            return result;
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previous);
            }
        }
    }
}
