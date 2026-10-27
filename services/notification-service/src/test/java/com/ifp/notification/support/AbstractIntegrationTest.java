package com.ifp.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * One cached context with disposable MySQL, RabbitMQ and MailHog containers. Mail is inspected through MailHog's HTTP
 * API, so the real SMTP path is exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.Containers.class)
public abstract class AbstractIntegrationTest {

    protected static final GenericContainer<?> MAILHOG = new GenericContainer<>(DockerImageName.parse("mailhog/mailhog:v1.0.1"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v2/messages").forPort(8025));

    static {
        MAILHOG.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        MySQLContainer<?> mysqlContainer() {
            return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("notification_db")
                    .withCommand("--default-time-zone=+00:00");
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILHOG::getHost);
        registry.add("spring.mail.port", () -> MAILHOG.getMappedPort(1025));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtSupport::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtSupport.AUDIENCE);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /** MailHog messages addressed to the given recipient. */
    protected JsonNode mailTo(String email) throws Exception {
        URI uri = URI.create("http://" + MAILHOG.getHost() + ":" + MAILHOG.getMappedPort(8025)
                + "/api/v2/search?kind=to&query=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }
}
