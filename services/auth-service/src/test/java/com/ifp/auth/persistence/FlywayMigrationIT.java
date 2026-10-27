package com.ifp.auth.persistence;

import com.ifp.auth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Test
    void migrationsAppliedAndHibernateOnlyValidates() {
        assertThat(ddlAuto).isEqualTo("validate");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String.class)).startsWith("1");
    }

    @Test
    void checkConstraintsRejectUnknownRolesAndStatuses() {
        assertThatThrownBy(() -> insertUser(uniqueEmail("bad-role"), "SUPERUSER", "ACTIVE"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_users_role");
        assertThatThrownBy(() -> insertUser(uniqueEmail("bad-status"), "SELLER", "LOCKED"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_users_status");
    }

    @Test
    void emailIsUniqueRegardlessOfCase() {
        String email = uniqueEmail("dup");
        insertUser(email, "SELLER", "ACTIVE");

        assertThatThrownBy(() -> insertUser(email.toUpperCase(), "SELLER", "ACTIVE"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_users_email");
    }

    private void insertUser(String email, String role, String status) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name, role, status, version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, '{noop}not-a-real-hash', 'Test', ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                UUID.randomUUID().toString(), email, role, status);
    }
}
