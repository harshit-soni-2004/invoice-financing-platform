package com.ifp.notification.web;

import com.ifp.notification.repository.NotificationRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record NotificationResponse(UUID id, String eventType, String subject, String status, String skipReason,
                                       Instant createdAt, Instant sentAt) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** The caller's own notifications, newest first. The owner is the token subject; no owner parameter exists. */
    @GetMapping
    public PageResponse<NotificationResponse> mine(Authentication authentication,
                                                   @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID owner = UUID.fromString(authentication.getName());
        long total = notifications.countForRecipient(owner);
        List<NotificationResponse> content = notifications.findForRecipient(owner, page, size).stream()
                .map(n -> new NotificationResponse(n.id(), n.eventType(), n.subject(), n.status(), n.skipReason(),
                        n.createdAt(), n.sentAt()))
                .toList();
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }
}
