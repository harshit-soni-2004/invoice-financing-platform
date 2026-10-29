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
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
    @Autowired
    private JdbcTemplate jdbc;

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
        assertThat(dead.getMessageProperties().getHeaders()).as("permanent failures skip the retry tiers")
                .doesNotContainKey(EventTopology.RETRY_COUNT_HEADER);
        assertThat(status(draft.getId())).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    void consumerQueueHasTheThreeDelayedRetryTiers() {
        for (Duration delay : EventTopology.DEFAULT_RETRY_DELAYS) {
            assertThat(amqpAdmin.getQueueProperties(EventTopology.retryQueueName(FinancingEventsListener.QUEUE, delay)))
                    .as("tier %s", delay).isNotNull();
        }
    }

    /**
     * Brokers that already hold the queue from the plain {@code consumerQueue} declaration must accept the new one:
     * the main queue and its DLQ keep their exact arguments, and redeclaring them the old way is not refused.
     */
    @Test
    void existingQueueAndDeadLetterQueueDeclarationsAreUnchanged() {
        Declarables before = EventTopology.consumerQueue(FinancingEventsListener.QUEUE, "financing.disbursed", "financing.settled");
        Map<String, Queue> after = new HashMap<>();
        FinancingEventsListener.financingEventsQueue().getDeclarablesByType(Queue.class).forEach(q -> after.put(q.getName(), q));

        for (Queue old : before.getDeclarablesByType(Queue.class)) {
            assertThat(after).containsKey(old.getName());
            assertThat(after.get(old.getName()).getArguments()).as(old.getName()).isEqualTo(old.getArguments());
            assertThat(after.get(old.getName()).isDurable()).isEqualTo(old.isDurable());
            assertThat(amqpAdmin.declareQueue(old)).as("broker accepts the old declaration of %s", old.getName())
                    .isEqualTo(old.getName());
        }
    }

    /**
     * A message parked in the first tier (as after one failed attempt) comes back to the consumer only after the tier
     * delay; a duplicate delivered directly in the meantime is applied once.
     */
    @Test
    void messageWaitingInARetryTierReturnsAfterItsDelayAndIsAppliedOnce() throws Exception {
        UUID id = listedInvoice().getId();
        String disbursed = envelope("FinancingDisbursed", id);
        UUID eventId = UUID.fromString(objectMapper.readTree(disbursed).path("eventId").asText());
        String tier = EventTopology.retryQueueName(FinancingEventsListener.QUEUE, EventTopology.DEFAULT_RETRY_DELAYS.getFirst());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        properties.setHeader(EventTopology.RETRY_COUNT_HEADER, 1);
        Instant parked = Instant.now();
        rabbit.send(EventTopology.RETRY_EXCHANGE, tier, new Message(disbursed.getBytes(StandardCharsets.UTF_8), properties));

        Thread.sleep(3000);
        assertThat(status(id)).as("still waiting in %s", tier).isEqualTo(InvoiceStatus.FINANCING_AVAILABLE);
        await(() -> status(id) == InvoiceStatus.FINANCED);
        assertThat(Duration.between(parked, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(9));

        send("financing.disbursed", disbursed);
        Thread.sleep(1500);
        assertThat(history.findByInvoiceIdOrderByOccurredAtAscIdAsc(id)).extracting(InvoiceStatusHistory::getToStatus)
                .containsOnlyOnce(InvoiceStatus.FINANCED);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = ? and event_id = UUID_TO_BIN(?)",
                Integer.class, FinancingEventsListener.CONSUMER, eventId.toString())).isEqualTo(1);
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
