package com.ifp.financing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.financing.domain.InvoiceSnapshot;
import com.ifp.financing.service.InvoiceProjectionService;
import com.ifp.messaging.EventEnvelope;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.InboundEventProcessor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feeds invoice-service's lifecycle events into the financeable-invoice read model. Snapshots are ordered by the
 * invoice row version inside {@link InvoiceProjectionService}, so late or reordered events cannot roll it back.
 */
@Configuration(proxyBeanMethods = false)
public class InvoiceEventsListener {

    public static final String QUEUE = "financing.invoice-events";
    static final String CONSUMER = "financing-service.invoice-projection";

    private final InboundEventProcessor processor;
    private final InvoiceProjectionService projection;
    private final ObjectMapper objectMapper;

    public InvoiceEventsListener(InboundEventProcessor processor, InvoiceProjectionService projection, ObjectMapper objectMapper) {
        this.processor = processor;
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    /** Transient failures wait in delay tiers (10 s, 1 min, 10 min) instead of blocking the listener thread. */
    @Bean
    static Declarables invoiceEventsQueue() {
        return EventTopology.consumerQueueWithRetries(QUEUE, "invoice.*");
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(Message message) {
        processor.process(message, CONSUMER, this::apply);
    }

    private void apply(EventEnvelope envelope) {
        InvoiceSnapshot snapshot;
        try {
            snapshot = objectMapper.treeToValue(envelope.payload(), InvoiceSnapshot.class);
        } catch (Exception e) {
            // Missing fields, unknown status, non-INR currency and similar: retrying cannot help.
            throw new AmqpRejectAndDontRequeueException("Invalid invoice snapshot: " + e.getClass().getSimpleName());
        }
        projection.apply(snapshot);
    }
}
