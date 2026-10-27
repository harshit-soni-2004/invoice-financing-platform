package com.ifp.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire format of every integration event (schema version 1). Payloads carry IDs, amounts, dates and statuses only;
 * the single exception is {@code UserRegistered}, whose email and display name feed notification-service's
 * recipient directory. Tokens and passwords never appear in events.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        UUID actorId,
        String correlationId,
        JsonNode payload) {

    public static final int SCHEMA_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }
}
