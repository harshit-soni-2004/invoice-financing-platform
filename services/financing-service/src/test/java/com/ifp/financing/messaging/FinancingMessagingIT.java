package com.ifp.financing.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.financing.domain.FinanceableInvoice;
import com.ifp.financing.domain.InvoiceState;
import com.ifp.financing.repository.FinanceableInvoiceRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class FinancingMessagingIT extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private FinanceableInvoiceRepository invoices;
    @Autowired
    private FinancingAgreementRepository agreements;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void invoiceEventsBuildTheReadModelAndIgnoreDuplicatesAndStaleVersions() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        String listed = invoiceEvent(invoiceId, sellerId, "FINANCING_AVAILABLE", 4, "INR");

        send("invoice.listed_for_financing", listed);
        await(() -> invoices.findById(invoiceId).isPresent());
        send("invoice.listed_for_financing", listed);
        send("invoice.submitted", invoiceEvent(invoiceId, sellerId, "SUBMITTED", 1, "INR"));
        Thread.sleep(1500);

        FinanceableInvoice projected = invoices.findById(invoiceId).orElseThrow();
        assertThat(projected.getInvoiceStatus()).isEqualTo(InvoiceState.FINANCING_AVAILABLE);
        assertThat(projected.getSourceVersion()).isEqualTo(4);
        assertThat(projected.getSellerId()).isEqualTo(sellerId);
        UUID listedEventId = UUID.fromString(objectMapper.readTree(listed).path("eventId").asText());
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = ? and event_id = UUID_TO_BIN(?)",
                Integer.class, InvoiceEventsListener.CONSUMER, listedEventId.toString())).isEqualTo(1);
    }

    @Test
    void invalidSnapshotIsDeadLetteredAndNotProjected() throws Exception {
        UUID invoiceId = UUID.randomUUID();

        send("invoice.listed_for_financing", invoiceEvent(invoiceId, UUID.randomUUID(), "FINANCING_AVAILABLE", 4, "USD"));

        Message dead = receiveContaining(InvoiceEventsListener.QUEUE + EventTopology.DLQ_SUFFIX, invoiceId.toString());
        assertThat(dead).as("dead-lettered snapshot").isNotNull();
        assertThat(invoices.findById(invoiceId)).isEmpty();
    }

    @Test
    void dealLifecyclePublishesEventsAndIdempotentReplaysPublishNothingNew() throws Exception {
        Queue probe = new Queue("test.probe." + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(EventTopology.EXCHANGE)).with("financing.#"));
        try {
            Deal deal = approvedDeal();
            String key = newKey();
            assertThat(disburse(deal.financierId(), deal.agreementId(), key).getResponse().getStatus()).isEqualTo(201);
            assertThat(disburse(deal.financierId(), deal.agreementId(), key).getResponse().getStatus()).isEqualTo(200);
            BigDecimal total = agreements.findById(deal.agreementId()).orElseThrow().getTotalRepayable();
            assertThat(repay(deal.sellerId(), deal.agreementId(), total.toPlainString(), newKey()).getResponse().getStatus()).isEqualTo(201);

            List<JsonNode> events = collect(probe, deal.invoiceId(), 7);
            assertThat(events).extracting(e -> e.path("eventType").asText()).containsExactly(
                    "FinancingOfferCreated", "FinancingOfferAccepted", "FinancingAgreementCreated", "FinancingApproved",
                    "FinancingDisbursed", "FinancingRepaymentRecorded", "FinancingSettled");
            JsonNode disbursed = events.get(4);
            assertThat(disbursed.path("actorId").asText()).isEqualTo(deal.financierId().toString());
            assertThat(disbursed.path("payload").path("sellerId").asText()).isEqualTo(deal.sellerId().toString());
            assertThat(disbursed.path("payload").path("feeAmount").decimalValue()).isPositive();
            assertThat(events.getLast().path("payload").path("status").asText()).isEqualTo("SETTLED");
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'FinancingDisbursed' and aggregate_id = ?",
                    Integer.class, deal.agreementId().toString())).isEqualTo(1);
        } finally {
            amqpAdmin.deleteQueue(probe.getName());
        }
    }

    @Test
    void rejectedRequestsLeaveNoEvents() throws Exception {
        Deal deal = pendingDeal();
        UUID stranger = UUID.randomUUID();

        assertThat(disburse(stranger, deal.agreementId(), newKey()).getResponse().getStatus()).isEqualTo(404);
        assertThat(disburse(deal.financierId(), deal.agreementId(), newKey()).getResponse().getStatus()).isEqualTo(409);

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type <> 'FinancingAgreementCreated'",
                Integer.class, deal.agreementId().toString())).isZero();
    }

    private String invoiceEvent(UUID invoiceId, UUID sellerId, String status, long version, String currency) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("invoiceId", invoiceId.toString())
                .put("sellerId", sellerId.toString())
                .put("invoiceNumber", "INV-" + invoiceId.toString().substring(0, 8))
                .put("buyerName", "Acme Buyers Pvt Ltd")
                .put("amount", "500000.00")
                .put("currency", currency)
                .put("issueDate", today().minusDays(1).toString())
                .put("dueDate", today().plusDays(60).toString())
                .put("status", status)
                .put("previousStatus", "VERIFIED")
                .put("sourceVersion", version)
                .put("sourceUpdatedAt", Instant.now().toString());
        String eventType = status.equals("FINANCING_AVAILABLE") ? "InvoiceListedForFinancing" : "InvoiceSubmitted";
        return objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(), eventType, EventEnvelope.SCHEMA_VERSION,
                Instant.now(), "invoice-service", "INVOICE", invoiceId.toString(), sellerId, null, payload));
    }

    private void send(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Message receiveContaining(String queue, String text) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(queue, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(text)) {
                return candidate;
            }
        }
        return null;
    }

    /** Events whose payload refers to the invoice, in publication order. */
    private List<JsonNode> collect(Queue probe, UUID invoiceId, int expected) throws Exception {
        List<JsonNode> mine = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(20);
        while (mine.size() < expected && Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(probe.getName(), 500);
            if (message != null) {
                JsonNode envelope = objectMapper.readTree(message.getBody());
                if (envelope.path("payload").path("invoiceId").asText().equals(invoiceId.toString())) {
                    mine.add(envelope);
                }
            }
        }
        // Anything else for this invoice (e.g. a duplicate disbursement event) would show up here.
        Message extra = rabbit.receive(probe.getName(), 1500);
        while (extra != null) {
            JsonNode envelope = objectMapper.readTree(extra.getBody());
            if (envelope.path("payload").path("invoiceId").asText().equals(invoiceId.toString())) {
                mine.add(envelope);
            }
            extra = rabbit.receive(probe.getName(), 500);
        }
        return mine;
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
