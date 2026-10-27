package com.ifp.invoice.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.invoice.domain.InvalidStatusTransitionException;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.service.InvoiceNotFoundException;
import com.ifp.invoice.service.InvoiceService;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.InboundEventProcessor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Moves invoices to FINANCED / SETTLED when financing-service reports a disbursement or settlement. Events that
 * can never apply (unknown invoice, impossible transition, malformed payload) are dead-lettered for investigation
 * instead of being retried.
 */
@Configuration(proxyBeanMethods = false)
public class FinancingEventsListener {

    public static final String QUEUE = "invoice.financing-events";
    static final String CONSUMER = "invoice-service.financing-outcomes";

    private final InboundEventProcessor processor;
    private final InvoiceService invoices;

    public FinancingEventsListener(InboundEventProcessor processor, InvoiceService invoices) {
        this.processor = processor;
        this.invoices = invoices;
    }

    @Bean
    static Declarables financingEventsQueue() {
        return EventTopology.consumerQueue(QUEUE, "financing.disbursed", "financing.settled");
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(Message message) {
        processor.process(message, CONSUMER, this::apply);
    }

    private void apply(EventEnvelope envelope) {
        InvoiceStatus target = switch (envelope.eventType()) {
            case "FinancingDisbursed" -> InvoiceStatus.FINANCED;
            case "FinancingSettled" -> InvoiceStatus.SETTLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        UUID invoiceId = invoiceId(envelope.payload());
        try {
            invoices.applyFinancingOutcome(invoiceId, target, envelope.actorId());
        } catch (InvoiceNotFoundException | InvalidStatusTransitionException e) {
            throw new AmqpRejectAndDontRequeueException("Financing outcome cannot be applied: " + e.getClass().getSimpleName());
        }
    }

    private static UUID invoiceId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("invoiceId").asText());
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Financing event without a valid invoiceId");
        }
    }
}
