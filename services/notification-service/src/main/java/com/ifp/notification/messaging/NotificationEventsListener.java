package com.ifp.notification.messaging;

import com.ifp.messaging.EventTopology;
import com.ifp.messaging.InboundEventProcessor;
import com.ifp.notification.service.NotificationService;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NotificationEventsListener {

    public static final String QUEUE = "notification.events";
    public static final String CONSUMER = "notification-service.notifier";

    private final InboundEventProcessor processor;
    private final NotificationService notifications;

    public NotificationEventsListener(InboundEventProcessor processor, NotificationService notifications) {
        this.processor = processor;
        this.notifications = notifications;
    }

    /** Transient failures (SMTP outages) wait in delay tiers (10 s, 1 min, 10 min) instead of blocking the listener thread. */
    @Bean
    static Declarables notificationEventsQueue() {
        return EventTopology.consumerQueueWithRetries(QUEUE, "user.registered", "user.profile-updated", "invoice.*",
                "financing.*");
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(Message message) {
        processor.process(message, CONSUMER, event -> {
            try {
                notifications.handle(event);
            } catch (IllegalArgumentException e) {
                // A missing or malformed party ID in the payload: retrying cannot fix it.
                throw new AmqpRejectAndDontRequeueException("Event payload is missing a party: " + e.getClass().getSimpleName());
            }
        });
    }
}
