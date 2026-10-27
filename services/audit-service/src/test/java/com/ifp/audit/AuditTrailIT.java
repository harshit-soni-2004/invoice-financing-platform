package com.ifp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.audit.messaging.AuditEventsListener;
import com.ifp.audit.support.AbstractIntegrationTest;
import com.ifp.audit.support.TestJwtSupport;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditTrailIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private JdbcTemplate jdbc;

    private final String admin = TestJwtSupport.admin(UUID.randomUUID());

    @Test
    void everyEventIsRecordedOnceWithoutContactDetails() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String event = envelope(UUID.randomUUID(), "UserRegistered", "USER", userId.toString(), userId, correlation.toString(),
                objectMapper.createObjectNode().put("userId", userId.toString()).put("email", "asha@example.test")
                        .put("displayName", "Asha").put("role", "SELLER"));

        publish("user.registered", event);
        publish("user.registered", event);
        await(() -> count(userId.toString()) == 1);
        Thread.sleep(1500);
        assertThat(count(userId.toString())).isEqualTo(1);

        MvcResult result = mockMvc.perform(get("/api/audit-events").param("aggregateType", "USER")
                        .param("aggregateId", userId.toString()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        JsonNode row = objectMapper.readTree(result.getResponse().getContentAsString()).path("content").get(0);
        assertThat(row.path("eventType").asText()).isEqualTo("UserRegistered");
        assertThat(row.path("actorId").asText()).isEqualTo(userId.toString());
        assertThat(row.path("correlationId").asText()).isEqualTo(correlation.toString());
        assertThat(row.path("payload").path("role").asText()).isEqualTo("SELLER");
        assertThat(row.path("payload").toString()).doesNotContain("asha@example.test").doesNotContain("Asha");
    }

    @Test
    void eventsOfAllProducersAreRecordedAndFilterable() throws Exception {
        String agreement = UUID.randomUUID().toString();
        publish("financing.disbursed", envelope(UUID.randomUUID(), "FinancingDisbursed", "AGREEMENT", agreement, null, null,
                objectMapper.createObjectNode().put("feeAmount", "123.45")));
        publish("financing.settled", envelope(UUID.randomUUID(), "FinancingSettled", "AGREEMENT", agreement, null, null,
                objectMapper.createObjectNode()));
        await(() -> count(agreement) == 2);

        mockMvc.perform(get("/api/audit-events").param("aggregateId", agreement).param("eventType", "FinancingSettled")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("FinancingSettled"));
        mockMvc.perform(get("/api/audit-events").param("aggregateId", agreement)
                        .param("from", Instant.now().plus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void auditTrailIsAppendOnlyForTheRuntimeUser() {
        String probe = UUID.randomUUID().toString();
        jdbc.update("""
                insert into audit_events (event_id, event_type, schema_version, producer, aggregate_type, aggregate_id,
                                          occurred_at, recorded_at, payload)
                values (UUID_TO_BIN(?), 'Probe', 1, 'test', 'TEST', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), '{}')""",
                UUID.randomUUID().toString(), probe);

        assertThatThrownBy(() -> jdbc.update("update audit_events set event_type = 'Tampered' where aggregate_id = ?", probe))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("UPDATE command denied");
        assertThatThrownBy(() -> jdbc.update("delete from audit_events where aggregate_id = ?", probe))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("DELETE command denied");
        assertThatThrownBy(() -> jdbc.execute("drop table audit_events"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("DROP command denied");
        assertThat(jdbc.queryForObject("select event_type from audit_events where aggregate_id = ?", String.class, probe))
                .isEqualTo("Probe");
    }

    @Test
    void malformedMessagesAreDeadLettered() {
        String marker = UUID.randomUUID().toString();
        MessageProperties properties = new MessageProperties();
        rabbit.send(EventTopology.EXCHANGE, "invoice.garbage", new Message(("not json " + marker).getBytes(StandardCharsets.UTF_8), properties));

        Message dead = null;
        Instant deadline = Instant.now().plusSeconds(20);
        while (dead == null && Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(AuditEventsListener.QUEUE + EventTopology.DLQ_SUFFIX, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(marker)) {
                dead = candidate;
            }
        }
        assertThat(dead).isNotNull();
    }

    @Test
    void onlyAdminsCanReadTheAuditTrail() throws Exception {
        mockMvc.perform(get("/api/audit-events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer " + TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer " + TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer "
                        + TestJwtSupport.token().subject(UUID.randomUUID().toString()).roles("ADMIN").signedByUntrustedKey().sign()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit-events").param("size", "0").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mockMvc.perform(get("/api/audit-events").param("aggregateType", "x' or '1'='1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    private String envelope(UUID eventId, String type, String aggregateType, String aggregateId, UUID actorId,
                            String correlationId, ObjectNode payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(eventId, type, EventEnvelope.SCHEMA_VERSION, Instant.now(),
                "test", aggregateType, aggregateId, actorId, correlationId, payload));
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private int count(String aggregateId) {
        Integer value = jdbc.queryForObject("select count(*) from audit_events where aggregate_id = ?", Integer.class, aggregateId);
        return value == null ? 0 : value;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 20s");
            }
            Thread.sleep(100);
        }
    }
}
