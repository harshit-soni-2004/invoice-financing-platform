package com.ifp.notification.repository;

import com.ifp.messaging.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class DeliveryAttemptRepository {

    private final JdbcTemplate jdbc;

    public DeliveryAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the event's transaction: a successful attempt is only recorded if the notification row commits too. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSent(UUID eventId, UUID recipientId, String eventType, Instant at) {
        insert(eventId, recipientId, eventType, "SENT", null, at);
    }

    /** Commits on its own, so the failure stays on record after the event's transaction rolls back for a retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(UUID eventId, UUID recipientId, String eventType, String error, Instant at) {
        insert(eventId, recipientId, eventType, "FAILED", error, at);
    }

    private void insert(UUID eventId, UUID recipientId, String eventType, String outcome, String error, Instant at) {
        jdbc.update("""
                        insert into delivery_attempts (event_id, recipient_id, event_type, outcome, error, attempted_at)
                        values (?, ?, ?, ?, ?, ?)""",
                Uuids.toBytes(eventId), Uuids.toBytes(recipientId), eventType, outcome, error, Timestamp.from(at));
    }
}
