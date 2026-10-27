package com.ifp.invoice;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the integration-test toolchain works against a real, disposable MySQL 8.4 server
 * (the same image used by docker-compose.yml), independent of the local Compose stack.
 */
class MySqlContainerConnectivityIT {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    @Test
    void connectsToDisposableMySqlRunsRealQueriesAndStopsContainer() throws Exception {
        MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("connectivity_test")
                .withCommand("--default-time-zone=+00:00");

        try (mysql) {
            mysql.start();

            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {

                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT VERSION(), @@global.time_zone, DATABASE()")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).startsWith("8.4.");
                    assertThat(rs.getString(2)).isEqualTo("+00:00");
                    assertThat(rs.getString(3)).isEqualTo("connectivity_test");
                }

                // Money must round-trip exactly through DECIMAL(19,2) <-> BigDecimal.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE money_probe (id INT PRIMARY KEY, amount DECIMAL(19,2) NOT NULL)");
                }
                BigDecimal amount = new BigDecimal("489468.49");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO money_probe (id, amount) VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setBigDecimal(2, amount);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT amount, amount + 0.01 FROM money_probe WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualTo(amount);
                    assertThat(rs.getBigDecimal(2)).isEqualTo(new BigDecimal("489468.50"));
                }
            }
        }

        assertThat(mysql.isRunning()).isFalse();
    }
}
