package com.ifp.invoice.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceDetails;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.domain.InvoiceStatusHistory;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceStatusHistoryRepository;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.security.Role;
import com.ifp.invoice.service.ExpectedVersion;
import com.ifp.invoice.service.InvoiceService;
import com.ifp.invoice.support.AbstractIntegrationTest;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceMessagingIT extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService service;
    @Autowired
    private InvoiceRepository invoices;
    @Autowired
    private InvoiceStatusHistoryRepository history;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;

    private final Actor seller = new Actor(UUID.randomUUID(), Set.of(Role.SELLER));
    private final Actor admin = new Actor(UUID.randomUUID(), Set.of(Role.ADMIN));

    @Test
    void lifecycleEventsCarryVersionedSnapshotsWithoutFreeText() throws Exception {
        Queue probe = probe("invoice.#");
        List<JsonNode> events;
        try {
            UUID id = listedInvoice().getId();
            events = collect(probe, id, 5);
        } finally {
            amqpAdmin.deleteQueue(probe.getName());
        }

        assertThat(events).extracting(e -> e.path("eventType").asText()).containsExactly(
                "InvoiceCreated", "InvoiceSubmitted", "InvoiceBuyerConfirmed", "InvoiceVerified", "InvoiceListedForFinancing");
        assertThat(events).extracting(e -> e.path("payload").path("sourceVersion").asLong()).containsExactly(0L, 1L, 2L, 3L, 4L);
        JsonNode listed = events.getLast();
        assertThat(listed.path("producer").asText()).isEqualTo("invoice-service");
        assertThat(listed.path("actorId").asText()).isEqualTo(seller.userId().toString());
        assertThat(listed.path("payload").path("status").asText()).isEqualTo("FINANCING_AVAILABLE");
        assertThat(listed.path("payload").path("previousStatus").asText()).isEqualTo("VERIFIED");
        assertThat(listed.path("payload").path("sellerId").asText()).isEqualTo(seller.userId().toString());
        assertThat(listed.path("payload").has("description")).isFalse();
        assertThat(listed.path("payload").has("buyerGstin")).isFalse();
    }

    @Test
    void disbursementAndSettlementMoveTheInvoiceAndRedeliveriesAreIgnored() throws Exception {
        UUID id = listedInvoice().getId();
        String disbursed = envelope("FinancingDisbursed", id);

        send("financing.disbursed", disbursed);
        await(() -> status(id) == InvoiceStatus.FINANCED);
        send("financing.disbursed", disbursed);
        send("financing.settled", envelope("FinancingSettled", id));
        await(() -> status(id) == InvoiceStatus.SETTLED);

        List<InvoiceStatusHistory> steps = history.findByInvoiceIdOrderByOccurredAtAscIdAsc(id);
        assertThat(steps).extracting(InvoiceStatusHistory::getToStatus).endsWith(InvoiceStatus.FINANCED, InvoiceStatus.SETTLED)
                .containsOnlyOnce(InvoiceStatus.FINANCED);
        assertThat(steps.getLast().getActorRole()).isEqualTo("SYSTEM");
        assertThat(steps.getLast().getActorId()).isEqualTo(admin.userId());
    }

    @Test
    void settlementArrivingFirstAppliesBothSteps() throws Exception {
        UUID id = listedInvoice().getId();

        send("financing.settled", envelope("FinancingSettled", id));
        await(() -> status(id) == InvoiceStatus.SETTLED);

        assertThat(history.findByInvoiceIdOrderByOccurredAtAscIdAsc(id)).extracting(InvoiceStatusHistory::getToStatus)
                .endsWith(InvoiceStatus.FINANCING_AVAILABLE, InvoiceStatus.FINANCED, InvoiceStatus.SETTLED);
    }

    @Test
    void impossibleOutcomeIsDeadLetteredWithoutChangingTheInvoice() throws Exception {
        Invoice draft = service.create(seller, details());
        String event = envelope("FinancingDisbursed", draft.getId());

        send("financing.disbursed", event);

        String dlq = FinancingEventsListener.QUEUE + EventTopology.DLQ_SUFFIX;
        Message dead = null;
        Instant deadline = Instant.now().plusSeconds(20);
        while (dead == null && Instant.now().isBefore(deadline)) {
            Message candidate = rabbit.receive(dlq, 1000);
            if (candidate != null && new String(candidate.getBody(), StandardCharsets.UTF_8).contains(draft.getId().toString())) {
                dead = candidate;
            }
        }
        assertThat(dead).as("dead-lettered message").isNotNull();
        assertThat(status(draft.getId())).isEqualTo(InvoiceStatus.DRAFT);
    }

    private Invoice listedInvoice() {
        UUID id = service.create(seller, details()).getId();
        service.submit(seller, id, ExpectedVersion.ANY);
        service.confirmBuyer(admin, id, ExpectedVersion.ANY);
        service.verify(admin, id, ExpectedVersion.ANY);
        return service.listForFinancing(seller, id, ExpectedVersion.ANY);
    }

    private static InvoiceDetails details() {
        return new InvoiceDetails(uniqueNumber(), "Acme Buyers Pvt Ltd", "27AAPFU0939F1ZV", "Steel coils",
                new BigDecimal("250000.00"), TODAY.minusDays(1), TODAY.plusDays(60));
    }

    private InvoiceStatus status(UUID id) {
        return invoices.findById(id).orElseThrow().getStatus();
    }

    private String envelope(String type, UUID invoiceId) throws Exception {
        JsonNode payload = objectMapper.valueToTree(Map.of("invoiceId", invoiceId, "agreementId", UUID.randomUUID()));
        return objectMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID(), type, EventEnvelope.SCHEMA_VERSION,
                Instant.now(), "financing-service", "AGREEMENT", UUID.randomUUID().toString(), admin.userId(), null, payload));
    }

    private void send(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Queue probe(String bindingKey) {
        // Not exclusive: the template may receive on a different connection than the admin declared on.
        Queue probe = new Queue("test.probe." + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(EventTopology.EXCHANGE)).with(bindingKey));
        return probe;
    }

    private List<JsonNode> collect(Queue probe, UUID aggregateId, int expected) throws Exception {
        List<JsonNode> mine = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(15);
        while (mine.size() < expected && Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(probe.getName(), 500);
            if (message != null) {
                JsonNode envelope = objectMapper.readTree(message.getBody());
                if (envelope.path("aggregateId").asText().equals(aggregateId.toString())) {
                    mine.add(envelope);
                }
            }
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
