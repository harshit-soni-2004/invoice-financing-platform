package com.ifp.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.common.web.CorrelationId;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Appends an event to the service's {@code outbox_events} table inside the caller's transaction, so the event
 * exists if and only if the business change commits. Publishing happens later in {@link OutboxRelay}.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String producer;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, String producer) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.producer = producer;
    }

    public EventEnvelope append(String routingKey, String eventType, String aggregateType, Object aggregateId,
                                UUID actorId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be written inside the business transaction");
        }
        Instant now = clock.instant();
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, EventEnvelope.SCHEMA_VERSION, now,
                producer, aggregateType, String.valueOf(aggregateId), actorId,
                CorrelationId.isValid(correlationId) ? correlationId : null, objectMapper.valueToTree(payload));
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event could not be serialized", e);
        }
        jdbc.update("""
                        insert into outbox_events (id, event_type, routing_key, aggregate_type, aggregate_id, envelope,
                                                   attempts, created_at, next_attempt_at)
                        values (?, ?, ?, ?, ?, ?, 0, ?, ?)""",
                Uuids.toBytes(envelope.eventId()), eventType, routingKey, aggregateType, envelope.aggregateId(), json,
                Timestamp.from(now), Timestamp.from(now));
        return envelope;
    }
}
