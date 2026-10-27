package com.ifp.notification.repository;

import com.ifp.messaging.Uuids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {

    public record Notification(UUID id, UUID eventId, String eventType, String subject, String status,
                               String skipReason, Instant createdAt, Instant sentAt) {
    }

    private static final RowMapper<Notification> MAPPER = (rs, n) -> new Notification(
            Uuids.fromBytes(rs.getBytes("id")), Uuids.fromBytes(rs.getBytes("event_id")), rs.getString("event_type"),
            rs.getString("subject"), rs.getString("status"), rs.getString("skip_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant());

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if this recipient was already handled for this event. */
    public boolean insertSent(UUID id, UUID eventId, UUID recipientId, String eventType, String subject, Instant now) {
        return insert(id, eventId, recipientId, eventType, subject, "SENT", null, now, now);
    }

    public boolean insertSkipped(UUID id, UUID eventId, UUID recipientId, String eventType, String subject,
                                 String reason, Instant now) {
        return insert(id, eventId, recipientId, eventType, subject, "SKIPPED", reason, now, null);
    }

    private boolean insert(UUID id, UUID eventId, UUID recipientId, String eventType, String subject, String status,
                           String reason, Instant now, Instant sentAt) {
        try {
            jdbc.update("""
                            insert into notifications (id, event_id, recipient_id, event_type, subject, status, skip_reason,
                                                       created_at, sent_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Uuids.toBytes(id), Uuids.toBytes(eventId), Uuids.toBytes(recipientId), eventType, subject, status,
                    reason, Timestamp.from(now), sentAt == null ? null : Timestamp.from(sentAt));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public List<Notification> findForRecipient(UUID recipientId, int page, int size) {
        return jdbc.query("""
                        select id, event_id, event_type, subject, status, skip_reason, created_at, sent_at
                        from notifications where recipient_id = ?
                        order by created_at desc, id desc
                        limit ? offset ?""",
                MAPPER, Uuids.toBytes(recipientId), size, (long) page * size);
    }

    public long countForRecipient(UUID recipientId) {
        Long count = jdbc.queryForObject("select count(*) from notifications where recipient_id = ?", Long.class,
                (Object) Uuids.toBytes(recipientId));
        return count == null ? 0 : count;
    }
}
