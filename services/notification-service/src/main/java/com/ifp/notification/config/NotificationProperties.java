package com.ifp.notification.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param from sender address of every notification email */
@Validated
@ConfigurationProperties("notification")
public record NotificationProperties(@NotBlank @Email String from) {
}
