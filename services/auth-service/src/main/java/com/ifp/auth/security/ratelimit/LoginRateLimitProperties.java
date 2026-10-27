package com.ifp.auth.security.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ipRequests    requests to one throttled endpoint allowed per client address within {@code ipWindow}
 * @param maxFailures   failed logins per client address + username within {@code failureWindow} before a lockout
 * @param lockout       how long that client address + username combination is then refused
 */
@Validated
@ConfigurationProperties("auth.rate-limit")
public record LoginRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30") @Min(1) int ipRequests,
        @DefaultValue("1m") @NotNull Duration ipWindow,
        @DefaultValue("5") @Min(1) int maxFailures,
        @DefaultValue("15m") @NotNull Duration failureWindow,
        @DefaultValue("5m") @NotNull Duration lockout) {
}
