package com.ifp.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.OutboxPurger;
import com.ifp.messaging.OutboxRelay;
import com.ifp.messaging.Uuids;
import com.ifp.notification.messaging.NotificationEventsListener;
import com.ifp.notification.support.AbstractIntegrationTest;
import com.ifp.notification.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationFlowIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectProvider<OutboxRelay> relay;
    @Autowired
    private ObjectProvider<OutboxPurger> purger;

    @Test
    void consumerOnlyServiceRunsNeitherRelayNorPurge() {
        assertThat(relay.getIfAvailable()).isNull();
        assertThat(purger.getIfAvailable()).isNull();
    }

    @Test
    void registeredUserGetsWelcomeMailAndLaterDealMailsExactlyOnce() throws Exception {
        UUID seller = UUID.randomUUID();
        String email = "seller-" + seller.toString().substring(0, 8) + "@example.test";
        send("user.registered", "UserRegistered", "USER", seller.toString(), mapper()
                .put("userId", seller.toString()).put("email", email).put("displayName", "Asha").put("role", "SELLER"));
        await(() -> mailTo(email).path("total").asInt() == 1);

        String verified = envelope("InvoiceVerified", "INVOICE", UUID.randomUUID().toString(), mapper()
                .put("sellerId", seller.toString()).put("invoiceNumber", "INV-42").put("amount", "500000.00"));
        publish("invoice.verified", verified);
        publish("invoice.verified", verified);
        await(() -> mailTo(email).path("total").asInt() == 2);
        Thread.sleep(1500);

        JsonNode mails = mailTo(email);
        assertThat(mails.path("total").asInt()).as("duplicate delivery must not send a second email").isEqualTo(2);
        String bodies = mails.path("items").toString();
        assertThat(bodies).contains("INV-42 verified").contains("Hello Asha").doesNotContain("500000");
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_id = ? and status = 'SENT'",
                Integer.class, (Object) Uuids.toBytes(seller))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from delivery_attempts where recipient_id = ? and outcome = 'SENT'",
                Integer.class, (Object) Uuids.toBytes(seller))).isEqualTo(2);
    }

    @Test
    void failedSendsAreRecordedPerAttemptThenDeadLettered() throws Exception {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publish("user.registered", envelope(eventId, "UserRegistered", "USER", user.toString(), mapper()
                .put("userId", user.toString()).put("email", "broken@@example.test").put("displayName", "Bo").put("role", "SELLER")));

        String dlq = NotificationEventsListener.QUEUE + EventTopology.DLQ_SUFFIX;
        Message dead = null;
        Instant deadline = Instant.now().plusSeconds(30);
        while (dead == null && Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(dlq, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(eventId.toString())) {
                dead = candidate;
            }
        }
        assertThat(dead).as("event dead-lettered after the last attempt").isNotNull();
        assertThat(jdbc.queryForList("select concat(outcome, ':', error) from delivery_attempts where event_id = UUID_TO_BIN(?)",
                String.class, eventId.toString())).hasSize(4).allSatisfy(row -> assertThat(row).startsWith("FAILED:Mail"));
        assertThat(count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId.toString())).isZero();
        assertThat(count("select count(*) from processed_events where event_id = UUID_TO_BIN(?)", eventId.toString())).isZero();
    }

    @Test
    void unknownRecipientIsRecordedAsSkippedWithoutMail() throws Exception {
        UUID stranger = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publish("financing.offer_created", envelope(eventId, "FinancingOfferCreated", "OFFER", UUID.randomUUID().toString(),
                mapper().put("sellerId", stranger.toString()).put("financierId", UUID.randomUUID().toString())));

        await(() -> count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId.toString()) == 1);
        assertThat(jdbc.queryForObject("select concat(status, ':', skip_reason) from notifications where event_id = UUID_TO_BIN(?)",
                String.class, eventId.toString())).isEqualTo("SKIPPED:RECIPIENT_UNKNOWN");
    }

    @Test
    void eventWithoutPartyIdsIsDeadLettered() throws Exception {
        UUID aggregate = UUID.randomUUID();
        publish("financing.settled", envelope(UUID.randomUUID(), "FinancingSettled", "AGREEMENT", aggregate.toString(), mapper()));

        String dlq = NotificationEventsListener.QUEUE + EventTopology.DLQ_SUFFIX;
        Message dead = null;
        Instant deadline = Instant.now().plusSeconds(20);
        while (dead == null && Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(dlq, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(aggregate.toString())) {
                dead = candidate;
            }
        }
        assertThat(dead).isNotNull();
    }

    @Test
    void usersSeeOnlyTheirOwnNotifications() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publish("financing.offer_accepted", envelope(eventId, "FinancingOfferAccepted", "OFFER", UUID.randomUUID().toString(),
                mapper().put("sellerId", other.toString()).put("financierId", owner.toString())));
        await(() -> count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId.toString()) == 1);

        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + TestJwtSupport.financier(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("FinancingOfferAccepted"))
                .andExpect(jsonPath("$.content[0].status").value("SKIPPED"));
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + TestJwtSupport.seller(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/notifications").param("size", "500")
                        .header("Authorization", "Bearer " + TestJwtSupport.seller(other)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void apiRequiresAValidTokenButHealthIsPublic() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer "
                        + TestJwtSupport.token().signedByUntrustedKey().sign()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer "
                        + TestJwtSupport.token().subject("not-a-uuid").sign()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private ObjectNode mapper() {
        return objectMapper.createObjectNode();
    }

    private String envelope(String type, String aggregateType, String aggregateId, ObjectNode payload) throws Exception {
        return envelope(UUID.randomUUID(), type, aggregateType, aggregateId, payload);
    }

    private String envelope(UUID eventId, String type, String aggregateType, String aggregateId, ObjectNode payload) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(eventId, type, EventEnvelope.SCHEMA_VERSION, Instant.now(),
                "test", aggregateType, aggregateId, null, null, payload));
    }

    private void send(String routingKey, String type, String aggregateType, String aggregateId, ObjectNode payload) throws Exception {
        publish(routingKey, envelope(type, aggregateType, aggregateId, payload));
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private int count(String sql, String arg) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arg);
        return value == null ? 0 : value;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.call()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 20s");
            }
            Thread.sleep(200);
        }
    }
}
