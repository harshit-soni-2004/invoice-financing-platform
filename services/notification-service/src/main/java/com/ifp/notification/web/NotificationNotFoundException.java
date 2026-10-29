package com.ifp.notification.web;

/** Also thrown for another user's notification, so its existence is not revealed. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Notification not found");
    }
}
