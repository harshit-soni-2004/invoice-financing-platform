package com.ifp.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.messaging.EventEnvelope;
import com.ifp.notification.config.NotificationProperties;
import com.ifp.notification.repository.DeliveryAttemptRepository;
import com.ifp.notification.repository.NotificationRepository;
import com.ifp.notification.repository.RecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles one event inside the consumer's transaction. The notification row is inserted before the mail is sent, so
 * the (event, recipient) unique key prevents a second send; if sending fails the transaction rolls back and the
 * listener retries. A crash between a successful send and the commit can still repeat one email (at-least-once).
 */
@Service
public class NotificationService {

    public static final String SKIP_UNKNOWN_RECIPIENT = "RECIPIENT_UNKNOWN";
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final RecipientRepository recipients;
    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final JavaMailSender mail;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationService(RecipientRepository recipients, NotificationRepository notifications,
                               DeliveryAttemptRepository attempts, JavaMailSender mail, NotificationProperties properties,
                               Clock clock) {
        this.recipients = recipients;
        this.notifications = notifications;
        this.attempts = attempts;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void handle(EventEnvelope event) {
        Instant now = clock.instant();
        // A profile update carries the same contact fields as the registration; neither sends mail on its own besides
        // the registration welcome.
        if ("UserRegistered".equals(event.eventType()) || "UserProfileUpdated".equals(event.eventType())) {
            JsonNode p = event.payload();
            recipients.upsert(UUID.fromString(p.path("userId").asText()), p.path("email").asText(),
                    p.path("displayName").asText(), p.path("role").asText(), now);
        }
        for (NotificationRules.Planned planned : NotificationRules.plan(event)) {
            Optional<RecipientRepository.Recipient> recipient = recipients.find(planned.recipientId());
            UUID notificationId = UUID.randomUUID();
            boolean inserted = recipient.isPresent()
                    ? notifications.insertSent(notificationId, event.eventId(), planned.recipientId(), event.eventType(), planned.subject(), now)
                    : notifications.insertSkipped(notificationId, event.eventId(), planned.recipientId(), event.eventType(),
                    planned.subject(), SKIP_UNKNOWN_RECIPIENT, now);
            if (!inserted) {
                continue;
            }
            if (recipient.isEmpty()) {
                log.info("Notification {} for event {} skipped: recipient unknown", notificationId, event.eventType());
                continue;
            }
            try {
                send(recipient.get(), planned);
            } catch (MailException e) {
                attempts.recordFailed(event.eventId(), planned.recipientId(), event.eventType(), e.getClass().getSimpleName(),
                        clock.instant());
                log.warn("Notification {} for event {} not sent: {}", notificationId, event.eventType(), e.getClass().getSimpleName());
                throw e;
            }
            attempts.recordSent(event.eventId(), planned.recipientId(), event.eventType(), clock.instant());
            log.info("Notification {} sent for event {}", notificationId, event.eventType());
        }
    }

    private void send(RecipientRepository.Recipient recipient, NotificationRules.Planned planned) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(recipient.email());
        message.setSubject(planned.subject());
        message.setText("Hello " + recipient.displayName() + ",\n\n" + planned.body() + "\n\n- Invoice Financing Platform (local demo)");
        mail.send(message);
    }
}
