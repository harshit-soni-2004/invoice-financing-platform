package com.ifp.financing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String DATABASE = "financing_db";

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName(DATABASE)
                .withCommand("--default-time-zone=+00:00");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        // A loaded Docker host can take over a minute to boot RabbitMQ; the default wait is 60 s.
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
