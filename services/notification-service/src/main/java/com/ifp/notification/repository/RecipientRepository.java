package com.ifp.notification.repository;

import com.ifp.messaging.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecipientRepository {

    public record Recipient(UUID userId, String email, String displayName, String role) {
    }

    private final JdbcTemplate jdbc;

    public RecipientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(UUID userId, String email, String displayName, String role, Instant now) {
        jdbc.update("""
                        insert into recipients (user_id, email, display_name, role, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?) as incoming
                        on duplicate key update email = incoming.email, display_name = incoming.display_name,
                                                role = incoming.role, updated_at = incoming.updated_at""",
                Uuids.toBytes(userId), email, displayName, role, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<Recipient> find(UUID userId) {
        return jdbc.query("select user_id, email, display_name, role from recipients where user_id = ?",
                (rs, n) -> new Recipient(Uuids.fromBytes(rs.getBytes("user_id")), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("role")),
                (Object) Uuids.toBytes(userId)).stream().findFirst();
    }
}
