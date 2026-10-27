package com.ifp.audit.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Reproduces production's privilege split in a disposable MySQL container: Flyway connects as audit_migrator (all
 * privileges on audit_db), the application as audit_svc (SELECT, INSERT). Passwords are random per run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.Containers.class)
public abstract class AbstractIntegrationTest {

    protected static final String DATABASE = "audit_db";
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(DATABASE)
            .withCommand("--default-time-zone=+00:00");
    protected static final String RUNTIME_PASSWORD = "rt_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MIGRATOR_PASSWORD = "mg_" + UUID.randomUUID().toString().replace("-", "");

    static {
        MYSQL.start();
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement sql = root.createStatement()) {
            sql.execute("CREATE USER 'audit_migrator'@'%' IDENTIFIED BY '" + MIGRATOR_PASSWORD + "'");
            sql.execute("CREATE USER 'audit_svc'@'%' IDENTIFIED BY '" + RUNTIME_PASSWORD + "'");
            sql.execute("GRANT ALL PRIVILEGES ON " + DATABASE + ".* TO 'audit_migrator'@'%'");
            sql.execute("GRANT SELECT, INSERT ON " + DATABASE + ".* TO 'audit_svc'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create audit test users", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> "audit_svc");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.user", () -> "audit_migrator");
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtSupport::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtSupport.AUDIENCE);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;
}
