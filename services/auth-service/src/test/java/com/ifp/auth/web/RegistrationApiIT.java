package com.ifp.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.UserAccount;
import com.ifp.messaging.EventTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationApiIT extends AbstractIntegrationTest {

    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sellerRegistersAndIsActive() throws Exception {
        String email = uniqueEmail("Seller");

        register(email, PASSWORD, "SELLER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        UserAccount stored = users.findByEmail(email.toLowerCase()).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
    }

    @Test
    void financierRegistersPendingApproval() throws Exception {
        register(uniqueEmail("fin"), PASSWORD, "FINANCIER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void adminSelfRegistrationIsRejected() throws Exception {
        String email = uniqueEmail("wannabe-admin");

        register(email, PASSWORD, "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        register(uniqueEmail("root"), PASSWORD, "SUPERUSER").andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailInAnyCaseIsConflict() throws Exception {
        String email = uniqueEmail("dup");
        register(email, PASSWORD, "SELLER").andExpect(status().isCreated());

        register(email.toUpperCase(), PASSWORD, "FINANCIER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void registrationPublishesUserRegisteredOnceAndNeverThePassword() throws Exception {
        // Not exclusive: the template may receive on a different connection than the admin declared on.
        Queue probe = new Queue("test.probe." + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(EventTopology.EXCHANGE)).with("user.registered"));
        String email = uniqueEmail("events");

        List<JsonNode> mine = new ArrayList<>();
        UUID userId;
        try {
            register(email, PASSWORD, "SELLER").andExpect(status().isCreated());
            register(email, PASSWORD, "SELLER").andExpect(status().isConflict());
            userId = users.findByEmail(email).orElseThrow().getId();

            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && mine.isEmpty()) {
                Message message = rabbitTemplate.receive(probe.getName(), 500);
                if (message != null) {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash");
                    JsonNode envelope = objectMapper.readTree(body);
                    if (envelope.path("aggregateId").asText().equals(userId.toString())) {
                        mine.add(envelope);
                    }
                }
            }
            // A second (duplicate-email) event would have arrived by now if one had been written.
            Message extra = rabbitTemplate.receive(probe.getName(), 1000);
            if (extra != null) {
                assertThat(objectMapper.readTree(extra.getBody()).path("aggregateId").asText()).isNotEqualTo(userId.toString());
            }
        } finally {
            amqpAdmin.deleteQueue(probe.getName());
        }
        assertThat(mine).hasSize(1);
        JsonNode envelope = mine.getFirst();
        assertThat(envelope.path("eventType").asText()).isEqualTo("UserRegistered");
        assertThat(envelope.path("producer").asText()).isEqualTo("auth-service");
        assertThat(envelope.path("payload").path("email").asText()).isEqualTo(email);
        assertThat(envelope.path("payload").path("role").asText()).isEqualTo("SELLER");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class,
                userId.toString())).isEqualTo(1);
    }

    @Test
    void weakPasswordIsRejectedWithoutEchoingIt() throws Exception {
        register(uniqueEmail("weak"), "hunter2-abc", "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(content().string(not(containsString("hunter2-abc"))));
    }

    @Test
    void otherApiPathsAreClosedWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageIsServedWithCsrfProtection() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mockMvc.perform(post("/login").param("username", "x@example.test").param("password", "whatever-long"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginPageLinksToTheAppAndLoadsItsAssetsWithoutASession() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(containsString("href=\"http://localhost:4200/register\"")))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")));
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("role=\"alert\"")));
        mockMvc.perform(get("/assets/login.css")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/login.js")).andExpect(status().isOk());
    }

    private ResultActions register(String email, String password, String role) throws Exception {
        String body = """
                {"email":"%s","password":"%s","displayName":"Test User","role":"%s"}""".formatted(email, password, role);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
