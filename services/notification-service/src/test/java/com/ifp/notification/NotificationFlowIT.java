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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void migrationsIndexReadStateAndProcessedEventsRetention() {
        assertThat(jdbc.queryForList(
                "select version from flyway_schema_history where success = 1 and version is not null order by installed_rank",
                String.class)).containsExactly("1", "700", "701");
        assertThat(jdbc.queryForList("""
                select distinct index_name from information_schema.statistics
                where table_schema = database() and table_name in ('processed_events', 'notifications')""", String.class))
                .contains("ix_processed_events_processed_at", "ix_notifications_recipient_read");
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
    void renamedUserIsGreetedByTheNewNameAndTheRenameItselfSendsNoMail() throws Exception {
        UUID seller = UUID.randomUUID();
        String email = "renamed-" + seller.toString().substring(0, 8) + "@example.test";
        send("user.registered", "UserRegistered", "USER", seller.toString(), mapper()
                .put("userId", seller.toString()).put("email", email).put("displayName", "Old Name").put("role", "SELLER"));
        await(() -> mailTo(email).path("total").asInt() == 1);

        UUID renamed = UUID.randomUUID();
        publish("user.profile-updated", envelope(renamed, "UserProfileUpdated", "USER", seller.toString(), mapper()
                .put("userId", seller.toString()).put("email", email).put("displayName", "New Name").put("role", "SELLER")));
        await(() -> count("select count(*) from recipients where user_id = UUID_TO_BIN(?) and display_name = 'New Name'",
                seller.toString()) == 1);

        publish("invoice.verified", envelope("InvoiceVerified", "INVOICE", UUID.randomUUID().toString(), mapper()
                .put("sellerId", seller.toString()).put("invoiceNumber", "INV-77").put("amount", "1000.00")));
        await(() -> mailTo(email).path("total").asInt() == 2);
        assertThat(mailTo(email).path("items").toString()).contains("Hello New Name");
        assertThat(count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", renamed.toString()))
                .isZero();
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
        assertThat(dead.getMessageProperties().getHeaders()).as("permanent failures skip the retry tiers")
                .doesNotContainKey(EventTopology.RETRY_COUNT_HEADER);
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
    void listResponseKeepsItsFieldsAndAppendsReadAt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publish("financing.offer_rejected", envelope(eventId, "FinancingOfferRejected", "OFFER", UUID.randomUUID().toString(),
                mapper().put("sellerId", UUID.randomUUID().toString()).put("financierId", owner.toString())));
        await(() -> count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", eventId.toString()) == 1);

        String body = mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + TestJwtSupport.financier(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(body);
        assertThat(fieldNames(page)).containsExactly("content", "page", "size", "totalElements", "totalPages");
        JsonNode item = page.path("content").get(0);
        assertThat(fieldNames(item))
                .containsExactly("id", "eventType", "subject", "status", "skipReason", "createdAt", "sentAt", "readAt");
        assertThat(item.get("readAt").isNull()).isTrue();
    }

    @Test
    void readStateBelongsToTheTokenOwnerAndIsIdempotent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String ownerToken = "Bearer " + TestJwtSupport.financier(owner);
        String otherToken = "Bearer " + TestJwtSupport.seller(other);
        // Disbursement notifies both parties; the offer rejection only the financier (owner).
        UUID disbursed = UUID.randomUUID();
        UUID declined = UUID.randomUUID();
        publish("financing.disbursed", envelope(disbursed, "FinancingDisbursed", "AGREEMENT", UUID.randomUUID().toString(),
                mapper().put("sellerId", other.toString()).put("financierId", owner.toString())));
        publish("financing.offer_rejected", envelope(declined, "FinancingOfferRejected", "OFFER", UUID.randomUUID().toString(),
                mapper().put("sellerId", other.toString()).put("financierId", owner.toString())));
        await(() -> count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", disbursed.toString()) == 2
                && count("select count(*) from notifications where event_id = UUID_TO_BIN(?)", declined.toString()) == 1);
        UUID ownersDisbursement = notificationId(disbursed, owner);
        UUID othersDisbursement = notificationId(disbursed, other);

        unreadCount(ownerToken, 2);
        unreadCount(otherToken, 1);

        // Someone else's notification looks exactly like a missing one, and is not modified.
        mockMvc.perform(post("/api/notifications/{id}/read", ownersDisbursement).header("Authorization", otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        mockMvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID()).header("Authorization", otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        unreadCount(ownerToken, 2);

        String first = mockMvc.perform(post("/api/notifications/{id}/read", ownersDisbursement).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownersDisbursement.toString()))
                .andExpect(jsonPath("$.readAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String readAt = objectMapper.readTree(first).path("readAt").asText();
        Thread.sleep(20);
        mockMvc.perform(post("/api/notifications/{id}/read", ownersDisbursement).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(readAt));
        unreadCount(ownerToken, 1);
        unreadCount(otherToken, 1);

        mockMvc.perform(post("/api/notifications/read-all").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").value(1));
        mockMvc.perform(post("/api/notifications/read-all").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").value(0));
        unreadCount(ownerToken, 0);
        unreadCount(otherToken, 1);
        assertThat(jdbc.queryForObject("select read_at is null from notifications where id = UUID_TO_BIN(?)", Boolean.class,
                othersDisbursement.toString())).as("other user's copy untouched").isTrue();
        mockMvc.perform(post("/api/notifications/{id}/read", ownersDisbursement).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(readAt));
    }

    @Test
    void readEndpointsRequireATokenAndAValidId() throws Exception {
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/not-a-uuid/read")
                        .header("Authorization", "Bearer " + TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
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

    private void unreadCount(String authorization, long expected) throws Exception {
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(expected));
    }

    private UUID notificationId(UUID eventId, UUID recipient) {
        return UUID.fromString(jdbc.queryForObject(
                "select BIN_TO_UUID(id) from notifications where event_id = UUID_TO_BIN(?) and recipient_id = UUID_TO_BIN(?)",
                String.class, eventId.toString(), recipient.toString()));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
