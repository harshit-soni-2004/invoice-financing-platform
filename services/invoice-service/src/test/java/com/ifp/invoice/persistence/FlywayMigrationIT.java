package com.ifp.invoice.persistence;

import com.ifp.invoice.support.AbstractIntegrationTest;
import com.ifp.invoice.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MySQLContainer<?> mysql;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Test
    void productionMigrationsAppliedAndHibernateOnlyValidates() {
        assertThat(ddlAuto).isEqualTo("validate");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "700");
    }

    @Test
    void processedEventsAreIndexedForTheRetentionJob() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'processed_events'
                  AND index_name = 'ix_processed_events_processed_at' AND column_name = 'processed_at'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void devSeedIsNotAppliedWithoutDevProfile() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE '%dev_demo%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE invoice_number LIKE 'DEMO-%'", Integer.class)).isZero();
    }

    @Test
    void checkConstraintsRejectInvalidRowsEvenWithoutTheApplication() {
        assertThatThrownBy(() -> insertInvoice("0.00", "INR", "2026-01-01", "2026-02-01", "DRAFT"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoices_amount_positive");
        assertThatThrownBy(() -> insertInvoice("10.00", "USD", "2026-01-01", "2026-02-01", "DRAFT"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoices_currency_inr");
        assertThatThrownBy(() -> insertInvoice("10.00", "INR", "2026-02-01", "2026-02-01", "DRAFT"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoices_due_after_issue");
        assertThatThrownBy(() -> insertInvoice("10.00", "INR", "2026-01-01", "2026-02-01", "PAID"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoices_status");
    }

    @Test
    void historyRequiresExistingInvoice() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO invoice_status_history (invoice_id, from_status, to_status, actor_id, actor_role, occurred_at)
                VALUES (UUID_TO_BIN(?), NULL, 'DRAFT', UUID_TO_BIN(?), 'SELLER', UTC_TIMESTAMP(6))""",
                UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_invoice_status_history_invoice");
    }

    @Test
    void devSeedMigratesCleanDatabaseAndIsIdempotent() throws Exception {
        String database = "seed_check_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = mysql.getJdbcUrl().replace("/" + TestcontainersConfiguration.DATABASE, "/" + database);
        Flyway flyway = Flyway.configure()
                .dataSource(url, "root", mysql.getPassword())
                .locations("classpath:db/migration", "classpath:db/seed-dev")
                .load();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(4);

        JdbcTemplate seedJdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, "root", mysql.getPassword()));
        assertThat(seedJdbc.queryForObject("SELECT COUNT(*) FROM invoices WHERE invoice_number LIKE 'DEMO-%'", Integer.class))
                .isEqualTo(4);
        assertThat(seedJdbc.queryForObject("SELECT COUNT(*) FROM invoice_status_history", Integer.class)).isEqualTo(12);

        // Force Flyway to re-run the repeatable seed, as it would after an edit; rows must not duplicate.
        seedJdbc.update("DELETE FROM flyway_schema_history WHERE script = 'R__dev_demo_invoices.sql'");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(seedJdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(4);
        assertThat(seedJdbc.queryForObject("SELECT COUNT(*) FROM invoice_status_history", Integer.class)).isEqualTo(12);
    }

    private void insertInvoice(String amount, String currency, String issueDate, String dueDate, String status) {
        jdbc.update("""
                INSERT INTO invoices (id, seller_id, invoice_number, buyer_name, amount, currency, issue_date, due_date,
                                      status, version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'Buyer', ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "N-" + UUID.randomUUID(),
                new java.math.BigDecimal(amount), currency, issueDate, dueDate, status);
    }
}
