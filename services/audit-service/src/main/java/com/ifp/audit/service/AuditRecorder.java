package com.ifp.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.audit.repository.AuditEventRepository;
import com.ifp.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Records every event once (the unique event ID makes redelivery a no-op), minus contact details. */
@Service
public class AuditRecorder {

    /** Personal contact data is kept by auth-service (and notification-service for mailing), not in the audit trail. */
    static final Set<String> REDACTED_FIELDS = Set.of("email", "displayName");
    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditRecorder(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** @return false if the event was already recorded */
    public boolean record(EventEnvelope event) {
        boolean inserted = events.insertIfAbsent(event, sanitize(event.payload()), clock.instant());
        if (inserted) {
            log.info("Audited event {} ({}) from {}", event.eventId(), event.eventType(), event.producer());
        } else {
            log.info("Event {} already audited", event.eventId());
        }
        return inserted;
    }

    static JsonNode sanitize(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode copy = payload.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (REDACTED_FIELDS.contains(field.getKey())) {
                    it.remove();
                } else {
                    strip(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AuditRecorder::strip);
        }
    }
}
