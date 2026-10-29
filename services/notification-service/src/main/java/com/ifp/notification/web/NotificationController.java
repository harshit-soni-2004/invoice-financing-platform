package com.ifp.notification.web;

import com.ifp.notification.repository.NotificationRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Every endpoint acts on the caller's own notifications. The owner is the token subject; no owner parameter exists. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    /** New fields are appended at the end so existing clients keep working. */
    public record NotificationResponse(UUID id, String eventType, String subject, String status, String skipReason,
                                       Instant createdAt, Instant sentAt, Instant readAt) {

        static NotificationResponse from(NotificationRepository.Notification n) {
            return new NotificationResponse(n.id(), n.eventType(), n.subject(), n.status(), n.skipReason(),
                    n.createdAt(), n.sentAt(), n.readAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record UnreadCountResponse(long unreadCount) {
    }

    public record MarkAllReadResponse(int markedRead) {
    }

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationController(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Newest first. */
    @GetMapping
    public PageResponse<NotificationResponse> mine(Authentication authentication,
                                                   @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID owner = owner(authentication);
        long total = notifications.countForRecipient(owner);
        List<NotificationResponse> content = notifications.findForRecipient(owner, page, size).stream()
                .map(NotificationResponse::from)
                .toList();
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(Authentication authentication) {
        return new UnreadCountResponse(notifications.countUnreadForRecipient(owner(authentication)));
    }

    /** Idempotent: an already-read notification keeps its original read time. 404 for unknown or someone else's. */
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
        UUID owner = owner(authentication);
        notifications.markRead(id, owner, clock.instant());
        return notifications.findForRecipient(id, owner).map(NotificationResponse::from)
                .orElseThrow(NotificationNotFoundException::new);
    }

    /** Idempotent: a repeat call marks nothing and reports 0. */
    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead(Authentication authentication) {
        return new MarkAllReadResponse(notifications.markAllRead(owner(authentication), clock.instant()));
    }

    private static UUID owner(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
