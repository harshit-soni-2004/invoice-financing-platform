package com.ifp.audit.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.Uuids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Insert and query only: the runtime database user cannot update or delete audit rows. */
@Repository
public class AuditEventRepository {

    public record AuditEvent(long id, UUID eventId, String eventType, int schemaVersion, String producer,
                             String aggregateType, String aggregateId, UUID actorId, String correlationId,
                             Instant occurredAt, Instant recordedAt, JsonNode payload) {
    }

    public record Filter(String eventType, String aggregateType, String aggregateId, UUID actorId, Instant from, Instant to) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AuditEvent> mapper;

    public AuditEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, n) -> new AuditEvent(rs.getLong("id"), Uuids.fromBytes(rs.getBytes("event_id")),
                rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("producer"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getBytes("actor_id") == null ? null : Uuids.fromBytes(rs.getBytes("actor_id")),
                rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(), readJson(rs.getString("payload")));
    }

    public boolean insertIfAbsent(EventEnvelope event, JsonNode sanitizedPayload, Instant recordedAt) {
        try {
            jdbc.update("""
                            insert into audit_events (event_id, event_type, schema_version, producer, aggregate_type, aggregate_id,
                                                      actor_id, correlation_id, occurred_at, recorded_at, payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Uuids.toBytes(event.eventId()), event.eventType(), event.schemaVersion(), event.producer(),
                    event.aggregateType(), event.aggregateId(), event.actorId() == null ? null : Uuids.toBytes(event.actorId()),
                    event.correlationId(), Timestamp.from(event.occurredAt()), Timestamp.from(recordedAt),
                    objectMapper.writeValueAsString(sanitizedPayload == null ? objectMapper.createObjectNode() : sanitizedPayload));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload could not be serialized", e);
        }
    }

    public List<AuditEvent> search(Filter filter, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("""
                select id, event_id, event_type, schema_version, producer, aggregate_type, aggregate_id, actor_id,
                       correlation_id, occurred_at, recorded_at, payload
                from audit_events""" + where + " order by occurred_at desc, id desc limit ? offset ?", mapper, args.toArray());
    }

    public long count(Filter filter) {
        List<Object> args = new ArrayList<>();
        Long count = jdbc.queryForObject("select count(*) from audit_events" + where(filter, args), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** Fixed column names; only values come from the request, and they are always bound as parameters. */
    private static String where(Filter filter, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (filter.eventType() != null) {
            clauses.add("event_type = ?");
            args.add(filter.eventType());
        }
        if (filter.aggregateType() != null) {
            clauses.add("aggregate_type = ?");
            args.add(filter.aggregateType());
        }
        if (filter.aggregateId() != null) {
            clauses.add("aggregate_id = ?");
            args.add(filter.aggregateId());
        }
        if (filter.actorId() != null) {
            clauses.add("actor_id = ?");
            args.add(Uuids.toBytes(filter.actorId()));
        }
        if (filter.from() != null) {
            clauses.add("occurred_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            clauses.add("occurred_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload is not valid JSON", e);
        }
    }
}
