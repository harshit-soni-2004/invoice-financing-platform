package com.ifp.audit.messaging;

import com.ifp.audit.service.AuditRecorder;
import com.ifp.messaging.EventTopology;
import com.ifp.messaging.InboundEventProcessor;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Receives every event on the exchange. Deduplication uses the audit table's unique event ID rather than
 * processed_events, because the audit row itself is the only effect.
 */
@Configuration(proxyBeanMethods = false)
public class AuditEventsListener {

    public static final String QUEUE = "audit.events";

    private final InboundEventProcessor processor;
    private final AuditRecorder recorder;

    public AuditEventsListener(InboundEventProcessor processor, AuditRecorder recorder) {
        this.processor = processor;
        this.recorder = recorder;
    }

    /** Transient failures wait in delay tiers (10 s, 1 min, 10 min) instead of blocking the listener thread. */
    @Bean
    static Declarables auditEventsQueue() {
        return EventTopology.consumerQueueWithRetries(QUEUE, "#");
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(Message message) {
        recorder.record(processor.parse(message));
    }
}
